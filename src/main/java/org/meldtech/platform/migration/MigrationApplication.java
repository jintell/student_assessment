package org.meldtech.platform.migration;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.meldtech.platform.migration.telemetry.MigrationClassification;
import org.meldtech.platform.migration.telemetry.MigrationMetrics;
import org.meldtech.platform.migration.telemetry.MigrationOtlpRegistry;
import org.meldtech.platform.migration.telemetry.MigrationOutcome;
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
        MigrationSessionSettings sessionSettings = MigrationSessionSettings.from(environment);
        MigrationClassification classification = classification(environment);

        return arguments -> {
            try (MigrationOtlpRegistry telemetry = MigrationOtlpRegistry.open(environment)) {
                String grantRefresh = UUID.randomUUID().toString();
                runMigrations(
                        classification,
                        telemetry.metrics(),
                        schema ->
                                flywayConfiguration(
                                                jdbcUrl,
                                                username,
                                                password,
                                                historySchema,
                                                grantRefresh,
                                                schema,
                                                sessionSettings)
                                        .load()
                                        .migrate(),
                        System::nanoTime);
            }
        };
    }

    static void runMigrations(
            MigrationClassification classification,
            MigrationMetrics metrics,
            Consumer<MigrationSchema> migrator,
            LongSupplier nanoTime) {
        try {
            for (MigrationSchema schema : MigrationSchema.values()) {
                long startedAt = nanoTime.getAsLong();
                try {
                    migrator.accept(schema);
                } finally {
                    long elapsedNanos = Math.max(0, nanoTime.getAsLong() - startedAt);
                    metrics.recordDuration(
                            schema.schemaName(),
                            classification,
                            java.time.Duration.ofNanos(elapsedNanos));
                }
            }
            metrics.recordOutcome(classification, MigrationOutcome.SUCCESS);
        } catch (RuntimeException exception) {
            MigrationOutcome outcome =
                    Thread.currentThread().isInterrupted()
                            ? MigrationOutcome.CANCELLED
                            : MigrationOutcome.EXECUTION_FAILED;
            metrics.recordOutcome(classification, outcome);
            throw exception;
        }
    }

    private static MigrationClassification classification(Environment environment) {
        String value = required(environment, "cbt.migration.classification");
        try {
            return MigrationClassification.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Migration classification must be EXPAND, MIGRATE, or CONTRACT", exception);
        }
    }

    static FluentConfiguration flywayConfiguration(
            String jdbcUrl,
            String username,
            String password,
            String historySchema,
            String grantRefresh,
            MigrationSchema schema,
            MigrationSessionSettings sessionSettings) {
        return Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .defaultSchema(historySchema)
                .schemas(historySchema)
                .createSchemas(true)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .mixed(true)
                .outOfOrder(false)
                .initSql(sessionSettings.flywayInitializationSql())
                .placeholders(Map.of("grantRefresh", grantRefresh))
                .table(schema.historyTable())
                .locations(schema.location());
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
