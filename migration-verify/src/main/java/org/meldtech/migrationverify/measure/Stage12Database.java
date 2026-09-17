package org.meldtech.migrationverify.measure;

import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class Stage12Database implements AutoCloseable {

    private static final long MEBIBYTE = 1024L * 1024L;
    private static final long MINIMUM_SHARED_MEMORY = 256L * MEBIBYTE;
    private static final long MAXIMUM_SHARED_MEMORY = 2048L * MEBIBYTE;
    private final PostgreSQLContainer container;

    public Stage12Database(String pinnedImage, long generatedRowCount) {
        validateImage(pinnedImage);
        DockerImageName image =
                DockerImageName.parse(pinnedImage).asCompatibleSubstituteFor("postgres");
        container =
                new PostgreSQLContainer(image)
                        .withDatabaseName("migration_stage_12")
                        .withUsername("stage12_owner")
                        .withPassword("stage12-local-only")
                        .withSharedMemorySize(requiredSharedMemoryBytes(generatedRowCount))
                        .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
                        .withCommand(
                                "postgres",
                                "-c",
                                "row_security=on",
                                "-c",
                                "default_transaction_isolation=read committed",
                                "-c",
                                "max_connections=40");
    }

    public void start() {
        container.start();
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public String username() {
        return container.getUsername();
    }

    public String password() {
        return container.getPassword();
    }

    @Override
    public void close() {
        container.close();
    }

    static long requiredSharedMemoryBytes(long generatedRowCount) {
        if (generatedRowCount < 0) {
            throw new IllegalArgumentException("Generated row count cannot be negative");
        }
        long estimated = Math.multiplyExact(generatedRowCount, 512L);
        return Math.max(MINIMUM_SHARED_MEMORY, Math.min(MAXIMUM_SHARED_MEMORY, estimated));
    }

    private static void validateImage(String image) {
        if (!image.startsWith("postgres:17@sha256:") || image.length() != 83) {
            throw new IllegalArgumentException(
                    "Stage 12 requires the approved immutable PostgreSQL 17 image digest");
        }
    }
}
