package org.meldtech.platform.platform.infra.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

    @Test
    void failsClosedForUnknownCyclicAndFailingValues() {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> cyclicValue = new HashMap<>();
        cyclicValue.put("self", cyclicValue);

        List<JsonNode> results =
                List.of(
                        serializer.serialize("customField", () -> null),
                        serializer.serialize(
                                "cyclicField", () -> objectMapper.valueToTree(cyclicValue)),
                        serializer.serialize(
                                "failingField",
                                () -> {
                                    throw new IllegalStateException("unsafe serializer diagnostic");
                                }));

        assertEquals(
                List.of(
                        RedactingJsonSerializer.REDACTED,
                        RedactingJsonSerializer.REDACTED,
                        RedactingJsonSerializer.REDACTED),
                results.stream().map(JsonNode::stringValue).toList());
    }

    private static JsonNode unsafeValue() {
        throw new AssertionError("A rejected telemetry value must not be evaluated");
    }
}
