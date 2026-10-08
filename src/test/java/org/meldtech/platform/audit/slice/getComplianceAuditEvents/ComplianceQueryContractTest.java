package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ComplianceQueryContractTest {

    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");

    @Test
    void rejectsMixedModesAndOversizedPages() {
        assertThatThrownBy(
                        () ->
                                new Request(
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of("result.attempt"),
                                        Optional.of("attempt-1"),
                                        Optional.of("grading.FAILURE_READ.v1"),
                                        Optional.empty(),
                                        100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Request(
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        501))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authenticatesCursorAndRejectsChangedBytes() {
        HmacAuditCursorCodec codec = new HmacAuditCursorCodec(new byte[32], new ObjectMapper());
        ComplianceCursor cursor =
                new ComplianceCursor(
                        1,
                        TENANT.toString(),
                        "filter",
                        Instant.parse("2026-10-08T12:00:00Z"),
                        Instant.parse("2026-10-07T12:00:00Z"),
                        "GENERAL_AUDIT_EVENT",
                        3,
                        42);
        String encoded = codec.encode(cursor);

        assertThat(codec.decode(encoded)).isEqualTo(cursor);
        String tampered = (encoded.charAt(0) == 'A' ? "B" : "A") + encoded.substring(1);
        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyRequiresAuthoritativeTenantWorkforceCapability() {
        ActorContext actor =
                ActorContext.tenantWorkforce(
                        new ActorId("officer-42"),
                        TENANT,
                        CorrelationId.parse("01K74Q5Y7B0000000000000000"),
                        SourceIp.parse("127.0.0.1"));
        Policy policy = new Policy((actorId, tenantId, capability) -> Mono.just(true));
        Request request =
                new Request(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        100);

        StepVerifier.create(policy.evaluate(actor, request))
                .expectNext(PolicyDecision.ALLOW)
                .verifyComplete();
    }
}
