package org.meldtech.migrationverify.measure;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.meldtech.migrationverify.adapter.parser.JSqlParserMigrationAdapter;
import org.meldtech.migrationverify.adapter.profile.YamlLockThresholdLoader;
import org.meldtech.migrationverify.adapter.profile.YamlVolumetricProfileLoader;
import org.meldtech.migrationverify.adapter.registry.YamlExamCriticalTableRegistry;
import org.meldtech.migrationverify.core.MigrationAnalysis;
import org.meldtech.migrationverify.policy.ClosedDdlAllowlist;
import org.meldtech.migrationverify.policy.MigrationAnalyser;
import org.meldtech.migrationverify.policy.MigrationHeaderParser;
import org.meldtech.migrationverify.release.ReleaseManifestClassificationCheck;
import org.meldtech.migrationverify.release.ReleaseManifestGenerator;
import org.meldtech.migrationverify.report.MigrationLockDurationReport;
import org.meldtech.migrationverify.report.MigrationLockReportEmitter;
import org.postgresql.PGConnection;
import tools.jackson.databind.json.JsonMapper;

public final class Stage12MigrationVerifier {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("V([0-9]+)__.+\\.sql");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public MigrationLockDurationReport verify(
            String pinnedImage,
            Path repository,
            Path manifestPath,
            Path profilePath,
            Path thresholdsPath,
            Path criticalRelationsPath,
            Path outputDirectory) {
        Path canonicalRepository = repository.toAbsolutePath().normalize();
        ReleaseManifestGenerator.ManifestSpecification manifest = readManifest(manifestPath);
        new ReleaseManifestClassificationCheck()
                .verify(
                        manifest.classification(),
                        manifest.migrations().stream()
                                .map(ReleaseManifestGenerator.MigrationSpecification::phase)
                                .toList());
        var profile = new YamlVolumetricProfileLoader().load(profilePath);
        DatasetManifest dataset =
                new DeterministicDatasetGenerator()
                        .generate(
                                profilePath,
                                profile,
                                profile.defaultSeed(),
                                profile.scale(),
                                outputDirectory.resolve("dataset"));
        long generatedRows =
                dataset.tables().stream().mapToLong(DatasetManifest.TableFile::rowCount).sum();

        try (var database = new Stage12Database(pinnedImage, generatedRows)) {
            database.start();
            try (Connection owner = connect(database);
                    Connection migrator = connect(database);
                    Connection observer = connect(database)) {
                prepareBaseline(owner, canonicalRepository, manifest);
                var loadedTables = new HashSet<String>();
                loadAvailableDataset(
                        owner, outputDirectory.resolve("dataset"), dataset, loadedTables);
                try (var setRole = migrator.createStatement()) {
                    setRole.execute("SET ROLE app_migrator");
                    setRole.execute("SET lock_timeout = '2000ms'");
                }
                MigrationLockDurationReport report =
                        measureRelease(
                                pinnedImage,
                                canonicalRepository,
                                manifestPath,
                                manifest,
                                profilePath,
                                thresholdsPath,
                                criticalRelationsPath,
                                dataset,
                                migrator,
                                observer);
                loadAvailableDataset(
                        owner, outputDirectory.resolve("dataset"), dataset, loadedTables);
                MigrationLockDurationReport emitted =
                        new MigrationLockReportEmitter()
                                .emit(
                                        report,
                                        outputDirectory.resolve(
                                                "migration-lock-duration-report.json"),
                                        outputDirectory.resolve(
                                                "migration-lock-duration-report.md"));
                if (!emitted.overallVerdict().equals("PASS")) {
                    throw new IllegalStateException(
                            "Stage 12 migration lock-duration gate failed; see " + outputDirectory);
                }
                return emitted;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Stage 12 migration verification failed", exception);
        }
    }

    private static MigrationLockDurationReport measureRelease(
            String pinnedImage,
            Path repository,
            Path manifestPath,
            ReleaseManifestGenerator.ManifestSpecification manifest,
            Path profilePath,
            Path thresholdsPath,
            Path criticalRelationsPath,
            DatasetManifest dataset,
            Connection migrator,
            Connection observer)
            throws SQLException {
        var thresholds = new YamlLockThresholdLoader().load(thresholdsPath);
        var thresholdPolicy =
                new LockThresholdPolicy(
                        thresholds, YamlExamCriticalTableRegistry.load(criticalRelationsPath));
        var analyser =
                new MigrationAnalyser(
                        new MigrationHeaderParser(),
                        new JSqlParserMigrationAdapter(),
                        new ClosedDdlAllowlist());
        var statements = new ArrayList<MigrationLockDurationReport.Statement>();
        var failures = new ArrayList<MigrationLockDurationReport.Failure>();
        long maximumGap = 0;
        boolean complete = true;
        int ordinal = 0;
        for (ReleaseManifestGenerator.MigrationSpecification migration : manifest.migrations()) {
            Path migrationPath = repository.resolve(migration.path()).normalize();
            requireRepositoryFile(repository, migrationPath);
            MigrationAnalysis analysis = analyser.analyse(migrationPath);
            if (!analysis.valid()) {
                throw new IllegalArgumentException(
                        "Manifest migration failed analysis: " + analysis.violations());
            }
            String sql = migrationBody(migrationPath);
            List<String> measuredSql =
                    migration.transactional()
                            ? List.of(sql, "SELECT pg_sleep(0.05)")
                            : List.of(sql);
            long startedAt = System.nanoTime();
            LockMeasurementResult measurement =
                    new LockMeasurementHarness()
                            .measure(migrator, observer, measuredSql, migration.transactional());
            long wallClockMillis =
                    java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            maximumGap = Math.max(maximumGap, measurement.maximumObservedGapMillis());
            complete &= measurement.complete();
            var locks = new ArrayList<MigrationLockDurationReport.Lock>();
            for (LockHoldMeasurement hold : measurement.holds()) {
                LockThresholdVerdict verdict = thresholdPolicy.evaluate(hold);
                locks.add(
                        new MigrationLockDurationReport.Lock(
                                hold.relation(),
                                hold.lockMode(),
                                hold.measuredHoldMillis(),
                                0,
                                new MigrationLockDurationReport.Threshold(
                                        verdict.policy(),
                                        verdict.warnMillis(),
                                        verdict.failMillis()),
                                verdict.verdict().name()));
                if (verdict.verdict() == LockVerdict.FAIL) {
                    failures.add(
                            new MigrationLockDurationReport.Failure(
                                    "LOCK_THRESHOLD_EXCEEDED",
                                    migration.path(),
                                    hold.statementOrdinal(),
                                    hold.relation(),
                                    "Measured "
                                            + hold.measuredHoldMillis()
                                            + "ms in "
                                            + hold.lockMode()));
                }
            }
            statements.add(
                    new MigrationLockDurationReport.Statement(
                            ++ordinal,
                            migration.path(),
                            checksum(sql.getBytes(StandardCharsets.UTF_8)),
                            analysis.statements().stream()
                                    .map(statement -> statement.kind().name())
                                    .distinct()
                                    .reduce((left, right) -> left + "," + right)
                                    .orElse("EMPTY"),
                            wallClockMillis,
                            locks));
        }
        if (!complete) {
            failures.add(
                    new MigrationLockDurationReport.Failure(
                            "INCOMPLETE_LOCK_SAMPLING", "", 0, "", "Lock sampling was incomplete"));
        }
        return new MigrationLockDurationReport(
                1,
                "stage12-" + Instant.now(),
                Instant.now().toString(),
                "PASS",
                new MigrationLockDurationReport.Release(
                        manifest.release(),
                        manifest.classification(),
                        checksum(readBytes(manifestPath)),
                        migrationSetChecksum(repository, manifest)),
                new MigrationLockDurationReport.Toolchain(
                        DeterministicDatasetGenerator.VERSION, "jsqlparser-5.3", pinnedImage),
                new MigrationLockDurationReport.Dataset(
                        dataset.seed(),
                        dataset.scale(),
                        dataset.generatorVersion(),
                        dataset.profileVersion(),
                        checksum(readBytes(profilePath)),
                        dataset.bundleChecksum()),
                new MigrationLockDurationReport.Sampling(
                        LockMeasurementHarness.SAMPLING_INTERVAL.toMillis(), maximumGap, complete),
                new MigrationLockDurationReport.ThresholdSource(
                        thresholdsPath.toString(), checksum(readBytes(thresholdsPath))),
                statements,
                failures);
    }

    private static void prepareBaseline(
            Connection connection,
            Path repository,
            ReleaseManifestGenerator.ManifestSpecification manifest)
            throws SQLException {
        executeFile(
                connection,
                repository.resolve(
                        "src/main/resources/db/provisioning/V1__create_migration_role.sql"));
        Path migrationRoot = repository.resolve("src/main/resources/db/migration");
        try (var modules = Files.list(migrationRoot);
                var statement = connection.createStatement()) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                String name = module.getFileName().toString();
                requireIdentifier(name);
                statement.execute(
                        "CREATE SCHEMA IF NOT EXISTS \"" + name + "\" AUTHORIZATION app_migrator");
                if (!Set.of("audit", "outbox", "platform").contains(name)) {
                    statement.execute(
                            "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_"
                                    + name
                                    + "') THEN CREATE ROLE app_"
                                    + name
                                    + "; END IF; END $$");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot discover migration schemas", exception);
        }
        Set<Path> applied = new HashSet<>();
        for (ReleaseManifestGenerator.MigrationSpecification migration : manifest.migrations()) {
            Path target = repository.resolve(migration.path()).normalize();
            int targetVersion = version(target);
            try (var candidates = Files.list(target.getParent())) {
                for (Path candidate :
                        candidates
                                .filter(
                                        path ->
                                                VERSIONED_MIGRATION
                                                        .matcher(path.getFileName().toString())
                                                        .matches())
                                .filter(path -> version(path) > 1 && version(path) < targetVersion)
                                .sorted(Comparator.comparingInt(Stage12MigrationVerifier::version))
                                .toList()) {
                    if (applied.add(candidate)) {
                        executeFile(connection, candidate);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot discover baseline migrations", exception);
            }
        }
    }

    private static void loadAvailableDataset(
            Connection connection,
            Path datasetDirectory,
            DatasetManifest dataset,
            Set<String> loadedTables)
            throws SQLException {
        for (DatasetManifest.TableFile table : dataset.tables()) {
            if (table.rowCount() == 0
                    || loadedTables.contains(table.name())
                    || !relationExists(connection, table.name())) {
                continue;
            }
            Path data = datasetDirectory.resolve(table.path()).normalize();
            List<String> header;
            try (var lines = Files.lines(data)) {
                header = List.of(lines.findFirst().orElseThrow().split(",", -1));
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Cannot read generated dataset: " + data, exception);
            }
            header.forEach(Stage12MigrationVerifier::requireIdentifier);
            String copy =
                    "COPY "
                            + qualifiedIdentifier(table.name())
                            + " ("
                            + header.stream()
                                    .map(column -> "\"" + column + "\"")
                                    .reduce((left, right) -> left + ", " + right)
                                    .orElseThrow()
                            + ") FROM STDIN WITH (FORMAT csv, HEADER true, NULL '\\N')";
            try (Reader reader = Files.newBufferedReader(data, StandardCharsets.UTF_8)) {
                connection.unwrap(PGConnection.class).getCopyAPI().copyIn(copy, reader);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Cannot load generated dataset: " + data, exception);
            }
            loadedTables.add(table.name());
        }
    }

    private static boolean relationExists(Connection connection, String relation)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, relation);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void executeFile(Connection connection, Path path) throws SQLException {
        requireRegularFile(path);
        try (var statement = connection.createStatement()) {
            statement.execute(new String(readBytes(path), StandardCharsets.UTF_8));
        }
    }

    private static ReleaseManifestGenerator.ManifestSpecification readManifest(Path path) {
        return JSON.readValue(path.toFile(), ReleaseManifestGenerator.ManifestSpecification.class);
    }

    private static Connection connect(Stage12Database database) throws SQLException {
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }

    private static String migrationBody(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .skip(4)
                    .reduce("", (left, right) -> left + right + "\n");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read migration: " + path, exception);
        }
    }

    private static String migrationSetChecksum(
            Path repository, ReleaseManifestGenerator.ManifestSpecification manifest) {
        MessageDigest digest = messageDigest();
        manifest.migrations().stream()
                .sorted(Comparator.comparing(ReleaseManifestGenerator.MigrationSpecification::path))
                .forEach(
                        migration -> {
                            digest.update(migration.path().getBytes(StandardCharsets.UTF_8));
                            digest.update((byte) 0);
                            digest.update(readBytes(repository.resolve(migration.path())));
                            digest.update((byte) '\n');
                        });
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static int version(Path migration) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(migration.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Expected versioned migration: " + migration);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String qualifiedIdentifier(String relation) {
        String[] parts = relation.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected schema-qualified relation: " + relation);
        }
        requireIdentifier(parts[0]);
        requireIdentifier(parts[1]);
        return "\"" + parts[0] + "\".\"" + parts[1] + "\"";
    }

    private static void requireIdentifier(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe PostgreSQL identifier: " + identifier);
        }
    }

    private static void requireRepositoryFile(Path repository, Path path) {
        if (!path.startsWith(repository)) {
            throw new IllegalArgumentException("Migration path escapes repository: " + path);
        }
        requireRegularFile(path);
    }

    private static void requireRegularFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Required file is missing: " + path);
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read file: " + path, exception);
        }
    }

    private static String checksum(byte[] value) {
        return "sha256:" + HexFormat.of().formatHex(messageDigest().digest(value));
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
