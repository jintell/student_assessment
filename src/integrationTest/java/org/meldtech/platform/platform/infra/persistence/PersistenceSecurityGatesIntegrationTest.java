package org.meldtech.platform.platform.infra.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.migration.MigrationApplication;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistenceSecurityGatesIntegrationTest {

    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();

    private PostgreSQLContainer postgres;

    @BeforeAll
    void migrateDatabase() throws Exception {
        postgres = PostgreSqlTestContainer.instance();
        postgres.start();
        executeAsClusterOwner(readResource("db/provisioning/V1__create_migration_role.sql"));
        executeAsClusterOwner("ALTER ROLE app_migrator PASSWORD '%s'".formatted(MIGRATOR_PASSWORD));
        MigrationApplication.run(
                new String[] {
                    "--migrate-only",
                    "--cbt.migration.jdbc-url=" + postgres.getJdbcUrl(),
                    "--cbt.migration.username=app_migrator",
                    "--cbt.migration.classification=EXPAND",
                    "--cbt.database.roles.app-migrator.password=" + MIGRATOR_PASSWORD
                });
    }

    @Test
    void everyTenantTableHasTheStrictForcedRlsPolicy() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        try (Connection connection = clusterOwnerConnection()) {
            PostgreSqlCatalogGate.verifyForcedRls(
                    connection,
                    matrix.schemas().stream().map(GrantMatrix.SchemaGrant::name).toList());
        }
    }

    @Test
    void noForeignKeySpansApplicationSchemas() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        try (Connection connection = clusterOwnerConnection()) {
            PostgreSqlCatalogGate.verifyNoCrossSchemaForeignKeys(
                    connection,
                    matrix.schemas().stream().map(GrantMatrix.SchemaGrant::name).toList());
        }
    }

    @Test
    void liveDatabaseGrantsExactlyMatchTheDeclaredMatrix() throws SQLException {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        try (Connection connection = clusterOwnerConnection()) {
            GrantDiffGate.verify(connection, matrix);
        }
    }

    private void executeAsClusterOwner(String sql) throws SQLException {
        try (Connection connection = clusterOwnerConnection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection clusterOwnerConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private String readResource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
