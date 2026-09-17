package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.migrationverify.adapter.profile.YamlVolumetricProfileLoader;

class DeterministicDatasetGeneratorTest {

    @TempDir Path temporaryDirectory;

    @Test
    void sameInputsProduceIdenticalRowsAndChecksums() {
        Path profilePath = Path.of("..", "migration", "volumetrics.yaml");
        var profile = new YamlVolumetricProfileLoader().load(profilePath);
        var generator = new DeterministicDatasetGenerator();

        DatasetManifest first =
                generator.generate(
                        profilePath, profile, 42, 1, temporaryDirectory.resolve("first"));
        DatasetManifest second =
                generator.generate(
                        profilePath, profile, 42, 1, temporaryDirectory.resolve("second"));

        assertEquals(first.bundleChecksum(), second.bundleChecksum());
        assertEquals(first.tables(), second.tables());
    }

    @Test
    void missingTableProfileFailsWithTableName() {
        Path profilePath = Path.of("..", "migration", "volumetrics.yaml");
        var profile = new YamlVolumetricProfileLoader().load(profilePath);
        var generator = new DeterministicDatasetGenerator();

        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                generator.verifyCoverage(
                                        profile, Set.of("platform.unprofiled_table")));

        assertEquals(
                "Missing volumetric profile rows: [platform.unprofiled_table]",
                failure.getMessage());
    }
}
