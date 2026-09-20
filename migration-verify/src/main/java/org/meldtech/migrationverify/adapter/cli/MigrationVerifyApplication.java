package org.meldtech.migrationverify.adapter.cli;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.meldtech.migrationverify.adapter.parser.JSqlParserMigrationAdapter;
import org.meldtech.migrationverify.adapter.profile.YamlVolumetricProfileLoader;
import org.meldtech.migrationverify.adapter.registry.YamlExamCriticalTableRegistry;
import org.meldtech.migrationverify.measure.DeterministicDatasetGenerator;
import org.meldtech.migrationverify.measure.InvalidIndexReconciler;
import org.meldtech.migrationverify.measure.Stage12DatabaseVerifier;
import org.meldtech.migrationverify.measure.Stage12MigrationVerifier;
import org.meldtech.migrationverify.policy.BlockingCriticalAlterCheck;
import org.meldtech.migrationverify.policy.ClosedDdlAllowlist;
import org.meldtech.migrationverify.policy.ColumnDropCheck;
import org.meldtech.migrationverify.policy.ColumnRenameCheck;
import org.meldtech.migrationverify.policy.DataModificationCheck;
import org.meldtech.migrationverify.policy.ForbiddenOperationPolicy;
import org.meldtech.migrationverify.policy.MigrationAnalyser;
import org.meldtech.migrationverify.policy.MigrationHeaderParser;
import org.meldtech.migrationverify.policy.NonConcurrentIndexCheck;
import org.meldtech.migrationverify.policy.NotNullWithoutDefaultCheck;
import org.meldtech.migrationverify.release.ReleaseManifestGenerator;
import tools.jackson.databind.json.JsonMapper;

public final class MigrationVerifyApplication {

    private MigrationVerifyApplication() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("A migration verification command is required");
        }

        switch (args[0]) {
            case "analyse" -> analyse(args);
            case "analyse-manifest" -> analyseManifest(args);
            case "generate-dataset" -> generateDataset(args);
            case "generate-manifest" -> generateManifest(args);
            case "reconcile-invalid-index" -> reconcileInvalidIndex(args);
            case "verify-stage12-database" -> verifyStage12Database(args);
            case "verify-stage12-migrations" -> verifyStage12Migrations(args);
            default ->
                    throw new IllegalArgumentException(
                            "Unknown migration verification command: " + args[0]);
        }
    }

    private static void analyse(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: analyse <exam-critical-registry> <migration> [migration ...]");
        }
        List<Path> migrations = java.util.Arrays.stream(args).skip(2).map(Path::of).toList();
        analyseMigrations(analyser(Path.of(args[1])), migrations);
    }

    private static void analyseMigrations(MigrationAnalyser analyser, List<Path> migrations) {
        var failures = new java.util.ArrayList<String>();
        for (Path migration : migrations) {
            analyser.analyse(migration).violations().stream()
                    .map(violation -> violation.code() + ": " + violation.message())
                    .forEach(failures::add);
        }
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException(
                    "Migration analysis failed:\n" + String.join("\n", failures));
        }
    }

    private static void analyseManifest(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: analyse-manifest <exam-critical-registry> <repository> "
                            + "<manifest-specification>");
        }
        Path repository = Path.of(args[2]).toAbsolutePath().normalize();
        var specification =
                JsonMapper.builder()
                        .build()
                        .readValue(
                                Path.of(args[3]).toFile(),
                                ReleaseManifestGenerator.ManifestSpecification.class);
        var migrations = new java.util.ArrayList<Path>();
        for (ReleaseManifestGenerator.MigrationSpecification migration :
                specification.migrations()) {
            Path path = repository.resolve(migration.path()).normalize();
            if (!path.startsWith(repository)) {
                throw new IllegalArgumentException(
                        "Migration path escapes the repository: " + migration.path());
            }
            migrations.add(path);
        }
        analyseMigrations(analyser(Path.of(args[1])), migrations);
    }

    private static MigrationAnalyser analyser(Path examCriticalRegistry) {
        var criticalRelations = YamlExamCriticalTableRegistry.load(examCriticalRegistry);
        return new MigrationAnalyser(
                new MigrationHeaderParser(),
                new JSqlParserMigrationAdapter(),
                new ClosedDdlAllowlist(),
                new ForbiddenOperationPolicy(
                        List.of(
                                new DataModificationCheck(),
                                new ColumnRenameCheck(),
                                new ColumnDropCheck(),
                                new NotNullWithoutDefaultCheck(),
                                new BlockingCriticalAlterCheck(criticalRelations),
                                new NonConcurrentIndexCheck())));
    }

    private static void generateDataset(String[] args) {
        if (args.length < 3 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Usage: generate-dataset <profile> <output-directory> [seed] [scale]");
        }
        Path profilePath = Path.of(args[1]);
        var profile = new YamlVolumetricProfileLoader().load(profilePath);
        long seed = args.length > 3 ? Long.parseLong(args[3]) : profile.defaultSeed();
        int scale = args.length > 4 ? Integer.parseInt(args[4]) : profile.scale();
        new DeterministicDatasetGenerator()
                .generate(profilePath, profile, seed, scale, Path.of(args[2]));
    }

    private static void generateManifest(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: generate-manifest <repository> <input-specification> <output>");
        }
        String previousImageDigest = System.getenv("CBT_PREVIOUS_IMAGE_DIGEST");
        new ReleaseManifestGenerator()
                .generate(
                        Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), previousImageDigest);
    }

    private static void verifyStage12Database(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: verify-stage12-database <image> <row-count> <report>");
        }
        new Stage12DatabaseVerifier().verify(args[1], Long.parseLong(args[2]), Path.of(args[3]));
    }

    private static void verifyStage12Migrations(String[] args) {
        if (args.length != 11) {
            throw new IllegalArgumentException(
                    "Usage: verify-stage12-migrations <image> <repository> <manifest-specification> "
                            + "<generated-manifest> <profile> <thresholds> <critical-relations> "
                            + "<probe-jar> <image-repository> <output-directory>");
        }
        new Stage12MigrationVerifier()
                .verify(
                        args[1],
                        Path.of(args[2]),
                        Path.of(args[3]),
                        Path.of(args[5]),
                        Path.of(args[6]),
                        Path.of(args[7]),
                        Path.of(args[10]),
                        new Stage12MigrationVerifier.CompatibilityConfiguration(
                                Path.of(args[4]), args[9], Path.of(args[8])));
    }

    private static void reconcileInvalidIndex(String[] args) {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: reconcile-invalid-index <jdbc-url> <module> <migration-version> "
                            + "<qualified-index>");
        }
        String username = requiredEnvironment("CBT_MIGRATION_USERNAME");
        if (!username.equals("app_migrator")) {
            throw new IllegalArgumentException(
                    "Invalid-index reconciliation must connect as app_migrator");
        }
        try (var connection =
                DriverManager.getConnection(
                        args[1], username, requiredEnvironment("CBT_MIGRATION_PASSWORD"))) {
            new InvalidIndexReconciler().reconcile(connection, args[2], args[3], args[4]);
        } catch (SQLException exception) {
            throw new IllegalStateException("Invalid-index reconciliation failed", exception);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required environment variable is missing: " + name);
        }
        return value;
    }
}
