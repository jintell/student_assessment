package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
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
    void profileChangesAreReflectedInGeneratedVolumes() throws IOException {
        Path baselinePath = Path.of("..", "migration", "volumetrics.yaml");
        String changedYaml =
                Files.readString(baselinePath)
                        .replace("profileVersion: \"2026.09.1\"", "profileVersion: \"2026.09.2\"")
                        .replace("rowCount: \"10000 * scale\"", "rowCount: \"12000 * scale\"");
        Path changedPath = temporaryDirectory.resolve("changed-volumetrics.yaml");
        Files.writeString(changedPath, changedYaml);
        var loader = new YamlVolumetricProfileLoader();
        var generator = new DeterministicDatasetGenerator();

        DatasetManifest baseline =
                generator.generate(
                        baselinePath,
                        loader.load(baselinePath),
                        42,
                        1,
                        temporaryDirectory.resolve("baseline"));
        DatasetManifest changed =
                generator.generate(
                        changedPath,
                        loader.load(changedPath),
                        42,
                        1,
                        temporaryDirectory.resolve("changed"));

        assertEquals(10_000, table(baseline, "platform.migration_fixture").rowCount());
        assertEquals(12_000, table(changed, "platform.migration_fixture").rowCount());
        assertEquals("2026.09.2", changed.profileVersion());
        assertNotEquals(baseline.bundleChecksum(), changed.bundleChecksum());
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

    private static DatasetManifest.TableFile table(DatasetManifest manifest, String name) {
        return manifest.tables().stream()
                .filter(table -> table.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
