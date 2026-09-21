package org.meldtech.migrationverify.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meldtech.migrationverify.measure.Stage12Database;

class NMinusOneBreakingChangeIntegrationTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String CASE_ID = "previous-release-obsolete-column-read";

    @Test
    void droppedColumnReadByPreviousReleaseFailsCompatibilityGate()
            throws IOException, SQLException {
        Path repository = Path.of("..").toAbsolutePath().normalize();
        try (var database = new Stage12Database(pinnedImage(repository), 1)) {
            database.start();
            try (Connection connection =
                    DriverManager.getConnection(
                            database.jdbcUrl(), database.username(), database.password())) {
                createPreviousVersionSchema(connection);
                dropColumnStillReadByPreviousVersion(connection);

                var runner =
                        new NMinusOneCompatibilityRunner(
                                ignored -> previousReleaseApplication(connection),
                                new CompatibilityCaseRegistry(List.of(previousReleaseReadCase())));

                var failure =
                        assertThrows(
                                IllegalStateException.class,
                                () -> runner.run(compatibilitySubject()));

                assertEquals("N-1 compatibility case failed: " + CASE_ID, failure.getMessage());
            }
        }
    }

    private static void createPreviousVersionSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA platform");
            statement.execute(
                    "CREATE TABLE platform.migration_fixture "
                            + "(fixture_id bigint PRIMARY KEY, obsolete_value text NOT NULL)");
            statement.execute(
                    "INSERT INTO platform.migration_fixture (fixture_id, obsolete_value) "
                            + "VALUES (1, 'retained')");
        }
    }

    private static void dropColumnStillReadByPreviousVersion(Connection connection)
            throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE platform.migration_fixture DROP COLUMN obsolete_value");
        }
    }

    private static CompatibilityCase previousReleaseReadCase() {
        return new CompatibilityCase() {
            @Override
            public String id() {
                return CASE_ID;
            }

            @Override
            public String relation() {
                return "platform.migration_fixture";
            }

            @Override
            public CompatibilityExecution execute(PreviousReleaseApplication application) {
                return application.execute(CASE_ID);
            }
        };
    }

    private static PreviousReleaseApplication previousReleaseApplication(Connection connection) {
        return new PreviousReleaseApplication() {
            @Override
            public String resolvedImageDigest() {
                return DIGEST;
            }

            @Override
            public boolean containsMigration(String migrationPath) {
                return false;
            }

            @Override
            public CompatibilityExecution execute(String caseId) {
                if (!CASE_ID.equals(caseId)) {
                    throw new IllegalArgumentException("Unknown compatibility case: " + caseId);
                }
                boolean existingReadPassed;
                try (var statement = connection.createStatement();
                        var result =
                                statement.executeQuery(
                                        "SELECT obsolete_value "
                                                + "FROM platform.migration_fixture "
                                                + "WHERE fixture_id = 1")) {
                    existingReadPassed = result.next();
                } catch (SQLException expectedBreakingChange) {
                    assertEquals("42703", expectedBreakingChange.getSQLState());
                    existingReadPassed = false;
                }
                return new CompatibilityExecution(existingReadPassed, true, true, true);
            }

            @Override
            public void close() {}
        };
    }

    private static CompatibilitySubject compatibilitySubject() {
        return new CompatibilitySubject(
                "0.0.1-SNAPSHOT",
                DIGEST,
                "sha256:" + "b".repeat(64),
                Set.of("src/main/resources/db/migration/platform/V8__breaking_contract.sql"),
                Set.of("platform.migration_fixture"));
    }

    private static String pinnedImage(Path repository) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(repository.resolve("gradle.properties"))) {
            properties.load(reader);
        }
        return properties.getProperty("postgresqlImage");
    }
}
