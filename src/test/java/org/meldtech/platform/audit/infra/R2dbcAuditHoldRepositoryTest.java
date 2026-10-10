package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.application.ActiveLegalHold;
import org.meldtech.platform.audit.application.DispositionRequest;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.test.StepVerifier;

class R2dbcAuditHoldRepositoryTest {

    private static final Instant START = Instant.parse("2025-08-01T00:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final DispositionRequest REQUEST =
            new DispositionRequest(
                    UUID.fromString("00000000-0000-0000-0000-000000000101"),
                    TenantId.parse("00000000-0000-0000-0000-000000000102"),
                    new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2025, 8)),
                    "general",
                    3,
                    START,
                    DUE,
                    "approval-1");

    @Test
    void persistsNormalizedHoldAndRestoresTheOriginalClock() {
        ScriptedR2dbc database =
                new ScriptedR2dbc(
                        sql -> {
                            if (sql.contains("WITH changed")) {
                                return ScriptedR2dbc.QueryResult.row(Map.of("changed", true));
                            }
                            return ScriptedR2dbc.QueryResult.row(
                                    Map.of(
                                            "request_id", REQUEST.requestId(),
                                            "original_retention_start", time(START),
                                            "original_due_at", time(DUE)));
                        });
        TransactionalConnection connection = database.connection()::createStatement;
        R2dbcAuditHoldRepository repository = new R2dbcAuditHoldRepository();
        List<ActiveLegalHold> holds =
                List.of(
                        new ActiveLegalHold("hold-b", "basis-b"),
                        new ActiveLegalHold("hold-a", "basis-a"),
                        new ActiveLegalHold("hold-a", "basis-a"));

        StepVerifier.create(
                        repository
                                .suspend(REQUEST, holds, NOW)
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        TransactionalConnection.class, connection)))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(
                        repository
                                .release(REQUEST, NOW)
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        TransactionalConnection.class, connection)))
                .assertNext(
                        resumed -> {
                            assertThat(resumed.requestId()).isEqualTo(REQUEST.requestId());
                            assertThat(resumed.originalRetentionStart()).isEqualTo(START);
                            assertThat(resumed.originalDueAt()).isEqualTo(DUE);
                        })
                .verifyComplete();

        String holdJson = (String) database.executions().getFirst().bindings().get(8);
        assertThat(holdJson).containsSubsequence("hold-a", "hold-b");
        assertThat(database.executions().get(1).bindings()).containsEntry(6, NOW);
    }

    @Test
    void failsClosedForEmptyHoldsChangedIdentityAndMissingTransaction() {
        R2dbcAuditHoldRepository repository = new R2dbcAuditHoldRepository();
        StepVerifier.create(repository.suspend(REQUEST, List.of(), NOW))
                .expectErrorMessage("suspension requires an active hold")
                .verify();
        StepVerifier.create(repository.release(REQUEST, NOW))
                .expectErrorMessage("No synchronous collaboration transaction is active")
                .verify();

        ScriptedR2dbc empty = new ScriptedR2dbc(sql -> ScriptedR2dbc.QueryResult.empty());
        TransactionalConnection connection = empty.connection()::createStatement;
        StepVerifier.create(
                        repository
                                .suspend(
                                        REQUEST,
                                        List.of(new ActiveLegalHold("hold-a", "basis-a")),
                                        NOW)
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        TransactionalConnection.class, connection)))
                .expectErrorMessage("hold suspension identity changed")
                .verify();
        StepVerifier.create(
                        repository
                                .release(REQUEST, NOW)
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        TransactionalConnection.class, connection)))
                .expectErrorMessage("held disposition was not found")
                .verify();
    }

    private static OffsetDateTime time(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
