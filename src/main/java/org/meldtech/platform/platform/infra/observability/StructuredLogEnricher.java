package org.meldtech.platform.platform.infra.observability;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.observability.BusinessEventCode;
import org.slf4j.MDC;

public final class StructuredLogEnricher {

    private final StructuredLogEventFactory eventFactory;

    public StructuredLogEnricher(Clock clock, StructuredLogEvent.RuntimeRole role) {
        eventFactory = new StructuredLogEventFactory(clock, role);
    }

    public StructuredLogEvent enrich(LogStatement statement) {
        Objects.requireNonNull(statement, "statement");
        CorrelationId correlationId =
                CorrelationId.parse(requireMdc(RequestContextPropagation.CORRELATION_ID_KEY));
        Optional<String> actorType = mdc(RequestContextPropagation.ACTOR_TYPE_KEY);
        Optional<String> actorId = mdc(RequestContextPropagation.ACTOR_ID_KEY);
        if (actorType.isPresent() != actorId.isPresent()) {
            throw new IllegalStateException(
                    "Logging context must contain actorType and actorId together");
        }

        return eventFactory.create(
                statement.level(),
                statement.logger(),
                statement.message(),
                correlationId,
                statement.traceId(),
                statement.spanId(),
                statement.module(),
                statement.slice(),
                actorType.map(StructuredLogEnricher::parseActorType),
                actorId.map(ActorId::new),
                mdc(RequestContextPropagation.TENANT_ID_KEY).map(TenantId::parse),
                statement.eventCode(),
                statement.errorCode(),
                statement.durationMs(),
                statement.dbQueryCount(),
                statement.error());
    }

    private static ActorType parseActorType(String value) {
        try {
            return ActorType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Logging context contains an invalid actorType");
        }
    }

    private static Optional<String> mdc(String key) {
        return Optional.ofNullable(MDC.get(key)).filter(value -> !value.isBlank());
    }

    private static String requireMdc(String key) {
        return mdc(key).orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Logging context is missing required field " + key));
    }

    public record LogStatement(
            StructuredLogEvent.Level level,
            String logger,
            String message,
            String traceId,
            String spanId,
            String module,
            String slice,
            Optional<BusinessEventCode> eventCode,
            Optional<String> errorCode,
            Optional<Long> durationMs,
            Optional<Long> dbQueryCount,
            Optional<StructuredLogEvent.StructuredError> error) {

        public LogStatement {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(logger, "logger");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(traceId, "traceId");
            Objects.requireNonNull(spanId, "spanId");
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(slice, "slice");
            Objects.requireNonNull(eventCode, "eventCode");
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(durationMs, "durationMs");
            Objects.requireNonNull(dbQueryCount, "dbQueryCount");
            Objects.requireNonNull(error, "error");
        }
    }
}
