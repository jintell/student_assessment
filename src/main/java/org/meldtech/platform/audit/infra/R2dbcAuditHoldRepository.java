package org.meldtech.platform.audit.infra;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.meldtech.platform.audit.application.ActiveLegalHold;
import org.meldtech.platform.audit.application.AuditHoldRepository;
import org.meldtech.platform.audit.application.DispositionRequest;
import org.meldtech.platform.audit.application.ResumedDisposition;
import org.meldtech.platform.platform.api.TransactionalConnection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL legal-hold state that joins the caller's audited tenant transaction. */
public final class R2dbcAuditHoldRepository implements AuditHoldRepository {

    private static final String SUSPEND_SQL =
            """
            WITH changed AS (
                INSERT INTO audit.audit_disposition_lifecycle (
                    request_id, tenant_id, retention_class, period,
                    policy_key, policy_version, state,
                    original_retention_start, original_due_at,
                    hold_references, hold_detected_at, released_at, updated_at
                ) VALUES (
                    $1, $2, $3, $4, $5, $6, 'HOLD_SUSPENDED',
                    $7, $8, $9::jsonb, $10, NULL, clock_timestamp()
                )
                ON CONFLICT (tenant_id, retention_class, period, policy_version)
                DO UPDATE SET
                    state = 'HOLD_SUSPENDED',
                    hold_references = EXCLUDED.hold_references,
                    hold_detected_at = LEAST(
                        audit.audit_disposition_lifecycle.hold_detected_at,
                        EXCLUDED.hold_detected_at
                    ),
                    released_at = NULL,
                    updated_at = clock_timestamp()
                WHERE audit.audit_disposition_lifecycle.request_id = EXCLUDED.request_id
                  AND audit.audit_disposition_lifecycle.policy_key = EXCLUDED.policy_key
                  AND audit.audit_disposition_lifecycle.original_retention_start =
                      EXCLUDED.original_retention_start
                  AND audit.audit_disposition_lifecycle.original_due_at = EXCLUDED.original_due_at
                  AND audit.audit_disposition_lifecycle.state IN ('ELIGIBLE', 'HOLD_SUSPENDED')
                  AND (
                      audit.audit_disposition_lifecycle.state <> 'HOLD_SUSPENDED'
                      OR audit.audit_disposition_lifecycle.hold_references IS DISTINCT FROM
                          EXCLUDED.hold_references
                  )
                RETURNING TRUE AS changed
            ), unchanged AS (
                SELECT FALSE AS changed
                FROM audit.audit_disposition_lifecycle lifecycle
                WHERE lifecycle.request_id = $1
                  AND lifecycle.tenant_id = $2
                  AND lifecycle.retention_class = $3
                  AND lifecycle.period = $4
                  AND lifecycle.policy_key = $5
                  AND lifecycle.policy_version = $6
                  AND lifecycle.original_retention_start = $7
                  AND lifecycle.original_due_at = $8
                  AND lifecycle.hold_references = $9::jsonb
                  AND lifecycle.state = 'HOLD_SUSPENDED'
            )
            SELECT changed FROM changed
            UNION ALL
            SELECT changed FROM unchanged WHERE NOT EXISTS (SELECT 1 FROM changed)
            """;

    private static final String RELEASE_SQL =
            """
            UPDATE audit.audit_disposition_lifecycle
            SET state = 'ELIGIBLE',
                hold_references = '[]'::jsonb,
                hold_detected_at = NULL,
                released_at = $7,
                updated_at = clock_timestamp()
            WHERE request_id = $1
              AND tenant_id = $2
              AND retention_class = $3
              AND period = $4
              AND policy_key = $5
              AND policy_version = $6
              AND state = 'HOLD_SUSPENDED'
            RETURNING request_id, original_retention_start, original_due_at
            """;

    private final ObjectMapper objectMapper;

    public R2dbcAuditHoldRepository() {
        this(new ObjectMapper());
    }

    R2dbcAuditHoldRepository(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Mono<Boolean> suspend(
            DispositionRequest request, List<ActiveLegalHold> holds, Instant detectedAt) {
        Objects.requireNonNull(request, "request");
        List<ActiveLegalHold> activeHolds = normalized(holds);
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (activeHolds.isEmpty()) {
            return Mono.error(new IllegalArgumentException("suspension requires an active hold"));
        }
        return TransactionalConnection.current()
                .flatMap(
                        connection -> {
                            Statement statement =
                                    bindIdentity(connection.createStatement(SUSPEND_SQL), request)
                                            .bind(6, request.originalRetentionStart())
                                            .bind(7, request.dueAt())
                                            .bind(8, holdJson(activeHolds))
                                            .bind(9, detectedAt);
                            return Flux.from(statement.execute())
                                    .flatMap(
                                            result ->
                                                    result.map(
                                                            (row, metadata) ->
                                                                    required(
                                                                            row,
                                                                            "changed",
                                                                            Boolean.class)))
                                    .singleOrEmpty()
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new IllegalStateException(
                                                            "hold suspension identity changed")));
                        });
    }

    @Override
    public Mono<ResumedDisposition> release(DispositionRequest request, Instant releasedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(releasedAt, "releasedAt");
        return TransactionalConnection.current()
                .flatMap(
                        connection -> {
                            Statement statement =
                                    bindIdentity(connection.createStatement(RELEASE_SQL), request)
                                            .bind(6, releasedAt);
                            return Flux.from(statement.execute())
                                    .flatMap(
                                            result ->
                                                    result.map(
                                                            (row, metadata) ->
                                                                    resumedDisposition(row)))
                                    .singleOrEmpty()
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new IllegalStateException(
                                                            "held disposition was not found")));
                        });
    }

    private static Statement bindIdentity(Statement statement, DispositionRequest request) {
        return statement
                .bind(0, request.requestId())
                .bind(1, UUID.fromString(request.tenantId().toString()))
                .bind(2, request.epoch().retentionClass().name())
                .bind(3, request.epoch().period().atDay(1))
                .bind(4, request.policyKey())
                .bind(5, request.policyVersion());
    }

    private String holdJson(List<ActiveLegalHold> holds) {
        List<Map<String, String>> references = new ArrayList<>(holds.size());
        for (ActiveLegalHold hold : holds) {
            Map<String, String> reference = new LinkedHashMap<>();
            reference.put("hold_reference", hold.holdReference());
            reference.put("legal_basis_reference", hold.legalBasisReference());
            references.add(reference);
        }
        try {
            return objectMapper.writeValueAsString(references);
        } catch (JacksonException exception) {
            throw new IllegalStateException("cannot serialize audit hold references", exception);
        }
    }

    private static List<ActiveLegalHold> normalized(List<ActiveLegalHold> holds) {
        return List.copyOf(holds).stream()
                .distinct()
                .sorted(
                        Comparator.comparing(ActiveLegalHold::holdReference)
                                .thenComparing(ActiveLegalHold::legalBasisReference))
                .toList();
    }

    private static ResumedDisposition resumedDisposition(Row row) {
        return new ResumedDisposition(
                required(row, "request_id", UUID.class),
                required(row, "original_retention_start", OffsetDateTime.class).toInstant(),
                required(row, "original_due_at", OffsetDateTime.class).toInstant());
    }

    private static <T> T required(Row row, String column, Class<T> type) {
        return Objects.requireNonNull(row.get(column, type), column);
    }
}
