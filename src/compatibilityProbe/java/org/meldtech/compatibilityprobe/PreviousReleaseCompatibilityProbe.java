package org.meldtech.compatibilityprobe;

import io.r2dbc.spi.Row;
import java.time.Duration;
import java.util.UUID;
import org.meldtech.platform.CbtPlatformApplication;
import org.meldtech.platform.platform.api.TransactionalCollaboration;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.api.RequestTenantId;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import reactor.core.publisher.Mono;

/** Runs compatibility operations through code loaded from the retained application image. */
public final class PreviousReleaseCompatibilityProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final RequestTenantId TENANT_ID =
            new RequestTenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
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
                new SpringApplicationBuilder(CbtPlatformApplication.class)
                        .web(WebApplicationType.NONE)
                        .profiles("api")
                        .properties("spring.flyway.enabled=false")
                        .run()) {
            var boundary = context.getBean(TransactionalCollaboration.class);
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

    private static String readValue(TransactionalCollaboration boundary, long fixtureId) {
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

    private static long insertOldRepresentation(TransactionalCollaboration boundary) {
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
            TransactionalCollaboration boundary, long fixtureId) {
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

    private static boolean readInvariant(TransactionalCollaboration boundary, long fixtureId) {
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
            TransactionalCollaboration boundary,
            java.util.function.Function<TransactionalConnection, Mono<T>> work) {
        return boundary.inExamEntryTransaction(TENANT_ID, work).block(TIMEOUT);
    }

    private static String string(Row row, int index) {
        String value = row.get(index, String.class);
        if (value == null) {
            throw new IllegalStateException("Compatibility query returned null");
        }
        return value;
    }
}
