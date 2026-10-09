package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.audit.domain.AuditPayloadPolicy.SecretAuditFieldException;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

class AuditPayloadPolicyTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "pin=synthetic-sensitive-value",
                "OTP : synthetic-sensitive-value",
                "failure: token = synthetic-sensitive-value",
                "password\n=synthetic-sensitive-value",
                "{\"accessToken\":\"synthetic-sensitive-value\"}",
                "{'client_secret': 'synthetic-sensitive-value'}",
                "https://example.invalid/?api_key=synthetic-sensitive-value",
                "Authorization: Bearer synthetic-sensitive-value",
                "credentials.password=synthetic-sensitive-value",
                "policy_key_backup=synthetic-sensitive-value",
                "policy_key=retention.default; token=synthetic-sensitive-value",
                "PIN_SECURITY_EVENT:2026-03-01; token=synthetic-sensitive-value",
                "PIN_SECURITY_EVENT:synthetic-sensitive-value"
            })
    void rejectsSecretAssignmentsInAnyStringLeaf(String message) {
        for (String field : List.of("exception_message", "reason", "details", "policy_key")) {
            for (var value :
                    List.of(
                            new StringValue(message),
                            new ArrayValue(List.of(new StringValue(message))),
                            new ArrayValue(
                                    List.of(
                                            new ObjectValue(
                                                    Map.of(
                                                            "message",
                                                            new StringValue(message))))))) {
                ObjectValue payload = new ObjectValue(Map.of(field, value));

                assertThatIllegalArgumentException()
                        .isThrownBy(() -> AuditPayloadPolicy.requireSecretFree(payload))
                        .isInstanceOf(SecretAuditFieldException.class)
                        .withMessageContaining(field)
                        .withMessageNotContaining("synthetic-sensitive-value")
                        .withNoCause();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "retention.default",
                "PIN validation failed",
                "password reset requested",
                "spinning=complete",
                "tokenization=complete",
                "policy_key=retention.default",
                "{\"policy_key\":\"retention.default\",\"result\":\"disposed\"}",
                "request_id=event-1; outcome: completed",
                "PIN_SECURITY_EVENT:2026-03-01",
                "GENERAL_AUDIT_EVENT:2026-03-01"
            })
    void permitsNonSecretTextAndAllowlistedAssignments(String message) {
        ObjectValue payload = new ObjectValue(Map.of("reason", new StringValue(message)));

        assertThatCode(() -> AuditPayloadPolicy.requireSecretFree(payload))
                .doesNotThrowAnyException();
    }

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
