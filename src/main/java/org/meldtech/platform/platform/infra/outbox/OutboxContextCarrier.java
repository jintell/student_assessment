package org.meldtech.platform.platform.infra.outbox;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import reactor.util.context.ContextView;

final class OutboxContextCarrier {

    static final String TRACEPARENT_KEY = "traceparent";
    static final String TRACESTATE_KEY = "tracestate";
    private static final Pattern TRACEPARENT =
            Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    CarriedContext capture(ContextView context, ActorContext suppliedActor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(suppliedActor, "suppliedActor");
        ActorContext ambientActor = context.getOrDefault(ActorContext.class, null);
        if (ambientActor == null) {
            throw new IllegalStateException("OUTBOX_CORRELATION_CONTEXT_MISSING");
        }
        String ambientCorrelation =
                context.getOrDefault(RequestContextPropagation.CORRELATION_ID_KEY, null);
        if (ambientCorrelation == null
                || !ambientActor.correlationId().equals(suppliedActor.correlationId())
                || !ambientCorrelation.equals(suppliedActor.correlationId().toString())) {
            throw new IllegalStateException("OUTBOX_CORRELATION_CONTEXT_MISMATCH");
        }
        Optional<String> traceparent = optionalString(context, TRACEPARENT_KEY);
        traceparent.ifPresent(
                value -> {
                    if (!TRACEPARENT.matcher(value).matches()) {
                        throw new IllegalArgumentException("Invalid W3C traceparent");
                    }
                });
        Optional<String> tracestate = optionalString(context, TRACESTATE_KEY);
        tracestate.ifPresent(
                value -> {
                    if (value.length() > 512) {
                        throw new IllegalArgumentException("W3C tracestate exceeds 512 characters");
                    }
                });
        return new CarriedContext(ambientCorrelation, traceparent, tracestate);
    }

    private static Optional<String> optionalString(ContextView context, String key) {
        Object value = context.getOrDefault(key, null);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return Optional.of(text);
    }

    record CarriedContext(
            String correlationId, Optional<String> traceparent, Optional<String> tracestate) {

        CarriedContext {
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(traceparent, "traceparent");
            Objects.requireNonNull(tracestate, "tracestate");
        }
    }
}
