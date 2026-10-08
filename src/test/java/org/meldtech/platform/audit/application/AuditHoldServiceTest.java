package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditHoldServiceTest {

    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final Instant DETECTED = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void suspendsTheWholePartitionAndEmitsOneIdempotentSuppressionRecord() {
        AtomicInteger suspensions = new AtomicInteger();
        List<AuditEvent> events = new CopyOnWriteArrayList<>();
        AuditHoldRepository repository = repository(suspensions);
        AuditHoldService service =
                new AuditHoldService(
                        repository,
                        (event, actor, occurredAt) -> {
                            events.add(event);
                            return Mono.empty();
                        });
        List<ActiveLegalHold> holds = List.of(new ActiveLegalHold("hold-42", "basis-7"));

        StepVerifier.create(service.suspendIfHeld(request(), holds, actor(), DETECTED))
                .expectNext(AuditHoldService.HoldResult.HOLD_SUSPENDED)
                .verifyComplete();
        StepVerifier.create(service.suspendIfHeld(request(), holds, actor(), DETECTED))
                .expectNext(AuditHoldService.HoldResult.HOLD_SUSPENDED)
                .verifyComplete();

        assertThat(events)
                .extracting(AuditEvent::eventType)
                .containsExactly(AuditHoldService.SUPPRESSED_EVENT);
    }

    @Test
    void releaseResumesFromTheOriginalClock() {
        AuditHoldService service =
                new AuditHoldService(
                        repository(new AtomicInteger()), (event, actor, time) -> Mono.empty());

        StepVerifier.create(
                        service.release(request(), List.of(), actor(), DETECTED.plusSeconds(90)))
                .assertNext(
                        resumed -> {
                            assertThat(resumed.originalRetentionStart())
                                    .isEqualTo(request().originalRetentionStart());
                            assertThat(resumed.originalDueAt()).isEqualTo(request().dueAt());
                        })
                .verifyComplete();
    }

    private static AuditHoldRepository repository(AtomicInteger suspensions) {
        return new AuditHoldRepository() {
            @Override
            public Mono<Boolean> suspend(
                    DispositionRequest request, List<ActiveLegalHold> holds, Instant detectedAt) {
                return Mono.just(suspensions.getAndIncrement() == 0);
            }

            @Override
            public Mono<ResumedDisposition> release(
                    DispositionRequest request, Instant releasedAt) {
                return Mono.just(
                        new ResumedDisposition(
                                request.requestId(),
                                request.originalRetentionStart(),
                                request.dueAt()));
            }
        };
    }

    private static DispositionRequest request() {
        return new DispositionRequest(
                UUID.fromString("01991a95-df27-7000-8000-000000000099"),
                TENANT,
                new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 8)),
                "audit.general.v3",
                3,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                "approval-42");
    }

    private static ActorContext actor() {
        return ActorContext.tenantSystem(
                SystemActor.RETENTION_ENGINE,
                TENANT,
                CorrelationId.parse("01K74Q5Y7B0000000000000000"),
                SourceIp.parse("127.0.0.1"));
    }
}
