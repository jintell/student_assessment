package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Stage12MigrationVerifierTest {

    @TempDir Path outputDirectory;

    @Test
    void fullReleaseMigrationSetPassesAgainstProductionShapedDataset() throws IOException {
        Path repository = Path.of("..").toAbsolutePath().normalize();

        var report =
                new Stage12MigrationVerifier()
                        .verify(
                                pinnedImage(repository),
                                repository,
                                repository.resolve("migration/release-manifest-input.json"),
                                repository.resolve("migration/volumetrics.yaml"),
                                repository.resolve("config/lock-duration-thresholds.yml"),
                                repository.resolve("migration/exam-critical-tables.yaml"),
                                outputDirectory);

        assertEquals("PASS", report.overallVerdict());
        assertEquals(1, report.statements().size());
        assertFalse(report.statements().getFirst().locks().isEmpty());
        assertTrue(
                report.statements().getFirst().locks().stream()
                        .noneMatch(lock -> lock.verdict().equals("FAIL")));
        assertTrue(
                Files.isRegularFile(
                        outputDirectory.resolve("migration-lock-duration-report.json")));
        assertTrue(
                Files.isRegularFile(outputDirectory.resolve("migration-lock-duration-report.md")));
    }

    private static String pinnedImage(Path repository) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(repository.resolve("gradle.properties"))) {
            properties.load(reader);
        }
        return properties.getProperty("postgresqlImage");
    }
}
