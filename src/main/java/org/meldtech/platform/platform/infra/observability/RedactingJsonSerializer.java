package org.meldtech.platform.platform.infra.observability;

import java.util.Objects;
import java.util.function.Supplier;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

final class RedactingJsonSerializer {

    static final String REDACTED = "[REDACTED]";

    private static final JsonNode REDACTED_NODE = JsonNodeFactory.instance.stringNode(REDACTED);
    private final TelemetryHealth health;

    RedactingJsonSerializer() {
        this(TelemetryHealth.NOOP);
    }

    RedactingJsonSerializer(TelemetryHealth health) {
        this.health = Objects.requireNonNull(health, "health");
    }

    JsonNode serialize(String fieldPath, Supplier<? extends JsonNode> valueSerializer) {
        Objects.requireNonNull(fieldPath, "fieldPath");
        Objects.requireNonNull(valueSerializer, "valueSerializer");
        if (SecretFieldPattern.isSecretField(fieldPath)) {
            health.redactionRejected(
                    ObservabilityHealthMetrics.Surface.LOG,
                    ObservabilityHealthMetrics.RedactionReason.PROHIBITED_FIELD);
            return REDACTED_NODE;
        }
        return Objects.requireNonNull(valueSerializer.get(), "serializedValue");
    }
}
