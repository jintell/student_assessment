package org.meldtech.migrationverify.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseManifestGeneratorTest {

    private static final String DIGEST = "sha256:" + "1".repeat(64);

    @TempDir Path repository;

    @Test
    void populatesManifestFromRepositoryMigrationBytes() throws IOException {
        Files.writeString(repository.resolve("migration.sql"), "SELECT 1;\n");
        Path specification = writeSpecification("EXPAND", "EXPAND");

        var manifest =
                new ReleaseManifestGenerator()
                        .generate(
                                repository,
                                specification,
                                repository.resolve("build/release.json"),
                                DIGEST);

        assertEquals("EXPAND", manifest.classification());
        assertEquals(DIGEST, manifest.previousImageDigest());
        assertEquals(1, manifest.migrations().size());
        assertEquals(71, manifest.migrationSetChecksum().length());
    }

    @Test
    void refusesMixedReleaseClassification() throws IOException {
        Files.writeString(repository.resolve("migration.sql"), "SELECT 1;\n");
        Path specification = writeSpecification("EXPAND", "CONTRACT");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ReleaseManifestGenerator()
                                .generate(
                                        repository,
                                        specification,
                                        repository.resolve("release.json"),
                                        DIGEST));
    }

    private Path writeSpecification(String classification, String phase) throws IOException {
        Path specification = repository.resolve("input.json");
        Files.writeString(
                specification,
                """
                {
                  "release": "test-release",
                  "classification": "%s",
                  "migrations": [{
                    "module": "platform",
                    "path": "migration.sql",
                    "phase": "%s",
                    "transactional": true
                  }]
                }
                """
                        .formatted(classification, phase));
        return specification;
    }
}
