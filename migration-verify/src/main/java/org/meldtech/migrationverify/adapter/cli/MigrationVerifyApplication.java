package org.meldtech.migrationverify.adapter.cli;

import java.nio.file.Path;
import org.meldtech.migrationverify.adapter.profile.YamlVolumetricProfileLoader;
import org.meldtech.migrationverify.measure.DeterministicDatasetGenerator;
import org.meldtech.migrationverify.measure.Stage12DatabaseVerifier;
import org.meldtech.migrationverify.release.ReleaseManifestGenerator;

public final class MigrationVerifyApplication {

    private MigrationVerifyApplication() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("A migration verification command is required");
        }

        switch (args[0]) {
            case "generate-dataset" -> generateDataset(args);
            case "generate-manifest" -> generateManifest(args);
            case "verify-stage12-database" -> verifyStage12Database(args);
            default ->
                    throw new IllegalArgumentException(
                            "Unknown migration verification command: " + args[0]);
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
}
