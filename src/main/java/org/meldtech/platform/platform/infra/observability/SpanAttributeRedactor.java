package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanBuilder;
import java.util.Objects;
import java.util.function.Supplier;

final class SpanAttributeRedactor {

    void set(SpanBuilder span, SpanAttributeName attribute, Supplier<String> value) {
        Objects.requireNonNull(attribute, "attribute");
        set(span, attribute.key(), value);
    }

    void set(SpanBuilder span, String attributeName, Supplier<String> value) {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(attributeName, "attributeName");
        Objects.requireNonNull(value, "value");
        String safeValue =
                TelemetryFieldPolicy.isForbidden(attributeName)
                        ? RedactingJsonSerializer.REDACTED
                        : Objects.requireNonNull(value.get(), "spanAttributeValue");
        span.setAttribute(AttributeKey.stringKey(attributeName), safeValue);
    }
}
