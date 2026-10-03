package org.meldtech.platform.platform.infra.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class RedactingJsonSerializerTest {

    private final RedactingJsonSerializer serializer = new RedactingJsonSerializer();

    @Test
    void preservesTheKernelApprovedPolicyKeySpellings() {
        JsonNode value = JsonNodeFactory.instance.stringNode("retention-five-year");

        for (String fieldPath :
                List.of("policy_key", "policyKey", "policy-key", "retention.policy_key")) {
            assertEquals(value, serializer.serialize(fieldPath, () -> value));
        }
    }

    @Test
    void redactsCanonicalSecretsAndEveryOtherKeyField() {
        for (String fieldPath :
                List.of(
                        "pin",
                        "otp",
                        "token",
                        "secret",
                        "password",
                        "authorization",
                        "api_key",
                        "encryption_key",
                        "privateKey",
                        "candidate_key",
                        "secret.policy_key")) {
            assertEquals(
                    RedactingJsonSerializer.REDACTED,
                    serializer
                            .serialize(fieldPath, RedactingJsonSerializerTest::unsafeValue)
                            .stringValue());
        }
    }

    @Test
    void doesNotEvaluateARejectedValue() {
        AtomicBoolean evaluated = new AtomicBoolean();

        JsonNode redacted =
                serializer.serialize(
                        "authorizationToken",
                        () -> {
                            evaluated.set(true);
                            return unsafeValue();
                        });

        assertFalse(evaluated.get());
        assertEquals(RedactingJsonSerializer.REDACTED, redacted.stringValue());
    }

    private static JsonNode unsafeValue() {
        throw new AssertionError("A rejected telemetry value must not be evaluated");
    }
}
