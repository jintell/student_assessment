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
import org.meldtech.migrationverify.policy.BlockingCriticalAlterCheck;
import org.meldtech.migrationverify.policy.ClosedDdlAllowlist;
import org.meldtech.migrationverify.policy.ColumnDropCheck;
import org.meldtech.migrationverify.policy.ColumnRenameCheck;
import org.meldtech.migrationverify.policy.ForbiddenOperationPolicy;
import org.meldtech.migrationverify.policy.MigrationAnalyser;
import org.meldtech.migrationverify.policy.MigrationHeaderParser;
import org.meldtech.migrationverify.policy.NonConcurrentIndexCheck;
import org.meldtech.migrationverify.policy.NotNullWithoutDefaultCheck;
import org.meldtech.migrationverify.release.ReleaseManifestGenerator;

public final class MigrationVerifyApplication {

    private MigrationVerifyApplication() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("A migration verification command is required");
        }

        switch (args[0]) {
            case "analyse" -> analyse(args);
            case "generate-dataset" -> generateDataset(args);
            case "generate-manifest" -> generateManifest(args);
            case "reconcile-invalid-index" -> reconcileInvalidIndex(args);
            case "verify-stage12-database" -> verifyStage12Database(args);
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
        var criticalRelations = YamlExamCriticalTableRegistry.load(Path.of(args[1]));
        var analyser =
                new MigrationAnalyser(
                        new MigrationHeaderParser(),
                        new JSqlParserMigrationAdapter(),
                        new ClosedDdlAllowlist(),
                        new ForbiddenOperationPolicy(
                                List.of(
                                        new ColumnRenameCheck(),
                                        new ColumnDropCheck(),
                                        new NotNullWithoutDefaultCheck(),
                                        new BlockingCriticalAlterCheck(criticalRelations),
                                        new NonConcurrentIndexCheck())));
        var failures = new java.util.ArrayList<String>();
        for (int index = 2; index < args.length; index++) {
            analyser.analyse(Path.of(args[index])).violations().stream()
                    .map(violation -> violation.code() + ": " + violation.message())
                    .forEach(failures::add);
        }
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException(
                    "Migration analysis failed:\n" + String.join("\n", failures));
        }
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
