package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanBuilder;
import java.util.Objects;
import java.util.function.Supplier;

final class SpanAttributeRedactor {

    private final TelemetryHealth health;

    SpanAttributeRedactor() {
        this(TelemetryHealth.NOOP);
    }

    SpanAttributeRedactor(TelemetryHealth health) {
        this.health = Objects.requireNonNull(health, "health");
    }

    void set(SpanBuilder span, SpanAttributeName attribute, Supplier<String> value) {
        Objects.requireNonNull(attribute, "attribute");
        set(span, attribute.key(), value);
    }

    void set(SpanBuilder span, String attributeName, Supplier<String> value) {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(attributeName, "attributeName");
        Objects.requireNonNull(value, "value");
        boolean forbidden = TelemetryFieldPolicy.isForbidden(attributeName);
        if (forbidden) {
            health.redactionRejected(
                    ObservabilityHealthMetrics.Surface.SPAN,
                    ObservabilityHealthMetrics.RedactionReason.PROHIBITED_FIELD);
        }
        String safeValue =
                forbidden
                        ? RedactingJsonSerializer.REDACTED
                        : Objects.requireNonNull(value.get(), "spanAttributeValue");
        span.setAttribute(AttributeKey.stringKey(attributeName), safeValue);
    }
}
