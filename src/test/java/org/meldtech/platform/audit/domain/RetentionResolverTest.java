package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.audit.RetentionDecision;
import org.meldtech.platform.shared.kernel.audit.RetentionHorizon;

class RetentionResolverTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-08T00:00:00Z");
    private static final AuditEvent EVENT =
            new AuditEvent(
                    "result.RESULT_PUBLISHED.v1",
                    new EntityRef("result.result", "result-1"),
                    Set.of(
                            RetentionClass.RESULT_PUBLICATION_EVIDENCE,
                            RetentionClass.GENERAL_AUDIT_EVENT),
                    new ObjectValue(Map.of()));

    @Test
    void anIndefiniteHorizonWinsAtWriteTime() {
        RetentionDecision decision =
                decision(
                        RetentionClass.RESULT_PUBLICATION_EVIDENCE,
                        Map.of(
                                RetentionClass.RESULT_PUBLICATION_EVIDENCE,
                                        RetentionHorizon.indefinite(),
                                RetentionClass.GENERAL_AUDIT_EVENT,
                                        RetentionHorizon.until(
                                                Instant.parse("2031-10-08T00:00:00Z"))));

        ResolvedRetention resolved =
                new RetentionResolver((time, eventType, entityType, candidates) -> decision)
                        .resolve(EVENT, OCCURRED_AT);

        assertThat(resolved.retentionClass()).isEqualTo(RetentionClass.RESULT_PUBLICATION_EVIDENCE);
        assertThat(resolved.policyVersion()).isEqualTo(7);
    }

    @Test
    void refusesAnInconsistentPolicyWinner() {
        RetentionDecision decision =
                decision(
                        RetentionClass.GENERAL_AUDIT_EVENT,
                        Map.of(
                                RetentionClass.RESULT_PUBLICATION_EVIDENCE,
                                        RetentionHorizon.indefinite(),
                                RetentionClass.GENERAL_AUDIT_EVENT,
                                        RetentionHorizon.until(
                                                Instant.parse("2031-10-08T00:00:00Z"))));

        RetentionResolver resolver =
                new RetentionResolver((time, eventType, entityType, candidates) -> decision);

        assertThatIllegalStateException().isThrownBy(() -> resolver.resolve(EVENT, OCCURRED_AT));
    }

    private static RetentionDecision decision(
            RetentionClass winner, Map<RetentionClass, RetentionHorizon> horizons) {
        return new RetentionDecision(
                "result.evidence",
                7,
                Instant.parse("2026-01-01T00:00:00Z"),
                Optional.empty(),
                horizons,
                winner);
    }
}
