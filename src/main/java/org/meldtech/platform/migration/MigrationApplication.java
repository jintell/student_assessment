package org.meldtech.platform.migration;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;

public final class MigrationApplication {

    private static final String MIGRATE_ONLY_ARGUMENT = "--migrate-only";

    private MigrationApplication() {}

    public static boolean isRequested(String[] args) {
        return Arrays.asList(args).contains(MIGRATE_ONLY_ARGUMENT);
    }

    public static void run(String[] args) {
        SpringApplication application = new SpringApplication(MigrationBootstrap.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.addInitializers(
                applicationContext -> {
                    if (!(applicationContext instanceof GenericApplicationContext context)) {
                        throw new IllegalStateException(
                                "Migration entrypoint requires a generic application context");
                    }
                    context.registerBean(
                            ApplicationRunner.class,
                            () -> migrationRunner(context.getEnvironment()));
                });
        application.run(args).close();
    }

    private static ApplicationRunner migrationRunner(Environment environment) {
        String jdbcUrl = required(environment, "cbt.migration.jdbc-url");
        String username = required(environment, "cbt.migration.username");
        String password = required(environment, "cbt.database.roles.app-migrator.password");
        if (!"app_migrator".equals(username)) {
            throw new IllegalStateException("Migration entrypoint must connect as app_migrator");
        }
        String historySchema =
                environment.getProperty("cbt.migration.history-schema", "platform_migrations");

        return arguments -> {
            for (MigrationSchema schema : MigrationSchema.values()) {
                Flyway.configure()
                        .dataSource(jdbcUrl, username, password)
                        .defaultSchema(historySchema)
                        .schemas(historySchema)
                        .createSchemas(true)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .table(schema.historyTable())
                        .locations(schema.location())
                        .load()
                        .migrate();
            }
        };
    }

    private static String required(Environment environment, String propertyName) {
        String value = environment.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required migration setting is missing: " + propertyName);
        }
        return value;
    }

    private static final class MigrationBootstrap {}
}
