package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

class AuditPayloadPolicyTest {

    @Test
    void permitsTheReviewedPolicyKeyField() {
        ObjectValue payload =
                new ObjectValue(
                        Map.of(
                                "policy_key", new StringValue("retention.default"),
                                "result", new StringValue("disposed")));

        assertThatCode(() -> AuditPayloadPolicy.requireSecretFree(payload))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSecretNamesAtAnyDepth() {
        ObjectValue payload =
                new ObjectValue(
                        Map.of(
                                "attempts",
                                new ArrayValue(
                                        List.of(
                                                new ObjectValue(
                                                        Map.of(
                                                                "oneTimeToken",
                                                                new StringValue("redacted")))))));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AuditPayloadPolicy.requireSecretFree(payload))
                .withMessageContaining("attempts.oneTimeToken");
    }

    @Test
    void doesNotTreatPolicyKeyPrefixesAsTheAllowlistedLeaf() {
        ObjectValue payload =
                new ObjectValue(Map.of("policy_key_backup", new StringValue("forbidden")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AuditPayloadPolicy.requireSecretFree(payload));
    }
}
