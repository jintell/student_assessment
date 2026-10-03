package org.meldtech.platform.platform.infra.observability;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.observability.BusinessEventCode;

final class StructuredLogEventFactory {

    private final Clock clock;
    private final StructuredLogEvent.RuntimeRole role;

    StructuredLogEventFactory(Clock clock, StructuredLogEvent.RuntimeRole role) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.role = Objects.requireNonNull(role, "role");
    }

    StructuredLogEvent create(
            StructuredLogEvent.Level level,
            String logger,
            String message,
            CorrelationId correlationId,
            String traceId,
            String spanId,
            String module,
            String slice,
            Optional<ActorType> actorType,
            Optional<ActorId> actorId,
            Optional<TenantId> tenantId,
            Optional<BusinessEventCode> eventCode,
            Optional<String> errorCode,
            Optional<Long> durationMs,
            Optional<Long> dbQueryCount,
            Optional<StructuredLogEvent.StructuredError> error) {
        return new StructuredLogEvent(
                clock.instant(),
                level,
                logger,
                message,
                correlationId,
                traceId,
                spanId,
                role,
                module,
                slice,
                actorType,
                actorId,
                tenantId,
                eventCode,
                errorCode,
                durationMs,
                dbQueryCount,
                error);
    }
}
