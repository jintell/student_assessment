package org.meldtech.compatibilityprobe;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

/** Runs compatibility operations through code loaded from the retained application image. */
public final class PreviousReleaseCompatibilityProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String APPLICATION_TYPE = "org.meldtech.platform.CbtPlatformApplication";
    private static final String TRANSACTION_TYPE =
            "org.meldtech.platform.platform.api.TransactionalCollaboration";
    private static final String CONNECTION_TYPE =
            "org.meldtech.platform.platform.api.TransactionalConnection";
    private static final String READ_VALUE_SQL =
            "SELECT nullable_value FROM platform.migration_fixture WHERE fixture_id = $1";
    private static final String INSERT_SQL =
            "INSERT INTO platform.migration_fixture "
                    + "(nullable_value, constraint_candidate, index_candidate, obsolete_value) "
                    + "VALUES ($1, $2, $3, $4) RETURNING fixture_id";
    private static final String UPDATE_SQL =
            "UPDATE platform.migration_fixture "
                    + "SET nullable_value = $1, constraint_candidate = $2 "
                    + "WHERE fixture_id = $3";
    private static final String READ_INVARIANT_SQL =
            "SELECT constant_default_value = 'baseline' "
                    + "AND constraint_candidate = 42 "
                    + "AND index_candidate = 'n-minus-one' "
                    + "AND obsolete_value = 'retained' "
                    + "FROM platform.migration_fixture WHERE fixture_id = $1";

    private PreviousReleaseCompatibilityProbe() {}

    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("contains-migration")) {
            String resource = args[1].replaceFirst("^src/main/resources/", "");
            System.out.println(
                    "CBT_MIGRATION_PRESENT="
                            + (PreviousReleaseCompatibilityProbe.class
                                            .getClassLoader()
                                            .getResource(resource)
                                    != null));
            return;
        }
        if (args.length != 1 || !args[0].equals("platform.migration_fixture.v6")) {
            throw new IllegalArgumentException("Unknown compatibility case");
        }
        try (var context =
                new SpringApplicationBuilder(requiredType(APPLICATION_TYPE))
                        .web(WebApplicationType.NONE)
                        .profiles("api")
                        .properties("spring.flyway.enabled=false")
                        .run()) {
            var boundary = RetainedTransactionBoundary.from(context);
            boolean existingRead = readValue(boundary, 1L).equals("seed");
            long createdId = insertOldRepresentation(boundary);
            boolean updated = updateOldRepresentation(boundary, createdId);
            boolean readBack = readValue(boundary, createdId).equals("updated");
            boolean persisted = readInvariant(boundary, createdId);
            System.out.printf(
                    "CBT_COMPATIBILITY_RESULT=%s,%s,%s,%s%n",
                    existingRead, createdId > 0 && updated, readBack, persisted);
        }
    }

    private static String readValue(RetainedTransactionBoundary boundary, long fixtureId) {
        return inTransaction(
                boundary,
                connection ->
                        Mono.from(
                                        connection
                                                .createStatement(READ_VALUE_SQL)
                                                .bind(0, fixtureId)
                                                .execute())
                                .flatMap(
                                        result ->
                                                Mono.from(
                                                        result.map(
                                                                (row, metadata) ->
                                                                        string(row, 0)))));
    }

    private static long insertOldRepresentation(RetainedTransactionBoundary boundary) {
        return inTransaction(
                boundary,
                connection ->
                        Mono.from(
                                        connection
                                                .createStatement(INSERT_SQL)
                                                .bind(0, "created")
                                                .bind(1, 41)
                                                .bind(2, "n-minus-one")
                                                .bind(3, "retained")
                                                .execute())
                                .flatMap(
                                        result ->
                                                Mono.from(
                                                        result.map(
                                                                (row, metadata) ->
                                                                        row.get(0, Long.class)))));
    }

    private static boolean updateOldRepresentation(
            RetainedTransactionBoundary boundary, long fixtureId) {
        Long updatedRows =
                inTransaction(
                        boundary,
                        connection ->
                                Mono.from(
                                                connection
                                                        .createStatement(UPDATE_SQL)
                                                        .bind(0, "updated")
                                                        .bind(1, 42)
                                                        .bind(2, fixtureId)
                                                        .execute())
                                        .flatMap(result -> Mono.from(result.getRowsUpdated())));
        return updatedRows == 1L;
    }

    private static boolean readInvariant(RetainedTransactionBoundary boundary, long fixtureId) {
        return inTransaction(
                boundary,
                connection ->
                        Mono.from(
                                        connection
                                                .createStatement(READ_INVARIANT_SQL)
                                                .bind(0, fixtureId)
                                                .execute())
                                .flatMap(
                                        result ->
                                                Mono.from(
                                                        result.map(
                                                                (row, metadata) ->
                                                                        Boolean.TRUE.equals(
                                                                                row.get(
                                                                                        0,
                                                                                        Boolean
                                                                                                .class))))));
    }

    private static <T> T inTransaction(
            RetainedTransactionBoundary boundary, Function<StatementFactory, Mono<T>> work) {
        return boundary.execute(work).block(TIMEOUT);
    }

    private static String string(Row row, int index) {
        String value = row.get(index, String.class);
        if (value == null) {
            throw new IllegalStateException("Compatibility query returned null");
        }
        return value;
    }

    private static Class<?> requiredType(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(
                    "Retained application type is unavailable: " + name, exception);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Retained application method is inaccessible", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Retained application method failed", cause);
        }
    }

    @FunctionalInterface
    private interface StatementFactory {

        Statement createStatement(String sql);
    }

    private static final class RetainedTransactionBoundary {

        private final Object target;
        private final Object tenantId;
        private final Method transaction;
        private final Method createStatement;

        private RetainedTransactionBoundary(
                Object target, Object tenantId, Method transaction, Method createStatement) {
            this.target = target;
            this.tenantId = tenantId;
            this.transaction = transaction;
            this.createStatement = createStatement;
        }

        static RetainedTransactionBoundary from(ApplicationContext context) {
            Class<?> transactionType = requiredType(TRANSACTION_TYPE);
            Method transaction =
                    java.util.Arrays.stream(transactionType.getMethods())
                            .filter(method -> method.getName().equals("inExamEntryTransaction"))
                            .filter(method -> method.getParameterCount() == 2)
                            .filter(
                                    method ->
                                            Function.class.isAssignableFrom(
                                                    method.getParameterTypes()[1]))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Retained transaction boundary is incompatible"));
            Object tenantId = tenantId(transaction.getParameterTypes()[0]);
            try {
                Method createStatement =
                        requiredType(CONNECTION_TYPE).getMethod("createStatement", String.class);
                return new RetainedTransactionBoundary(
                        context.getBean(transactionType), tenantId, transaction, createStatement);
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException(
                        "Retained transaction connection is incompatible", exception);
            }
        }

        @SuppressWarnings("unchecked")
        <T> Mono<T> execute(Function<StatementFactory, Mono<T>> work) {
            Function<Object, Mono<T>> retainedWork =
                    connection ->
                            work.apply(sql -> (Statement) invoke(createStatement, connection, sql));
            Object result = invoke(transaction, target, tenantId, retainedWork);
            if (!(result instanceof Mono<?> mono)) {
                throw new IllegalStateException("Retained transaction boundary returned no Mono");
            }
            return (Mono<T>) mono;
        }

        private static Object tenantId(Class<?> tenantType) {
            try {
                try {
                    return invoke(tenantType.getMethod("parse", String.class), null, TENANT_ID);
                } catch (NoSuchMethodException ignored) {
                    return tenantType
                            .getConstructor(UUID.class)
                            .newInstance(UUID.fromString(TENANT_ID));
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(
                        "Retained tenant identifier is incompatible: " + tenantType.getName(),
                        exception);
            }
        }
    }
}
