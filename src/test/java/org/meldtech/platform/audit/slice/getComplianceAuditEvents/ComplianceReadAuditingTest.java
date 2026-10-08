package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.application.PrivilegedReadEventType;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ComplianceReadAuditingTest {

    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    void appendsReadEvidenceAfterTheQueryAndBeforeReturningThePage() {
        List<String> actions = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<AuditEvent> emitted = new AtomicReference<>();
        Queries queries =
                (tenantId, request, asOf, after, limit) -> {
                    actions.add("query");
                    assertThat(tenantId).isEqualTo(TENANT);
                    return Mono.just(List.of());
                };
        Handler handler =
                new Handler(
                        queries,
                        new HmacAuditCursorCodec(new byte[32], new ObjectMapper()),
                        request -> true,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (event, actor, occurredAt) -> {
                            actions.add("audit");
                            emitted.set(event);
                            return Mono.empty();
                        });

        StepVerifier.create(handler.handle(actor(), request()))
                .assertNext(response -> assertThat(response.items()).isEmpty())
                .verifyComplete();

        assertThat(actions).containsExactly("query", "audit");
        assertThat(java.util.Objects.requireNonNull(emitted.get()).eventType())
                .isEqualTo("audit.COMPLIANCE_AUDIT_EVENTS_READ.v1");
        assertThat(java.util.Arrays.stream(PrivilegedReadEventType.values()))
                .allMatch(value -> value.eventType().contains("_READ.v"));
    }

    private static Request request() {
        return new Request(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                100);
    }

    private static ActorContext actor() {
        return ActorContext.tenantWorkforce(
                new ActorId("officer-42"),
                TENANT,
                CorrelationId.parse("01K74Q5Y7B0000000000000000"),
                SourceIp.parse("127.0.0.1"));
    }
}
