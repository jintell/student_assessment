package org.meldtech.platform.platform.infra.observability;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.observability.BusinessEventCode;

public record StructuredLogEvent(
        Instant timestamp,
        Level level,
        String logger,
        String message,
        CorrelationId correlationId,
        String traceId,
        String spanId,
        RuntimeRole role,
        String module,
        String slice,
        Optional<ActorType> actorType,
        Optional<ActorId> actorId,
        Optional<TenantId> tenantId,
        Optional<BusinessEventCode> eventCode,
        Optional<String> errorCode,
        Optional<Long> durationMs,
        Optional<Long> dbQueryCount,
        Optional<StructuredError> error) {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9]*");
    private static final Pattern SLICE = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    public StructuredLogEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level");
        logger = requireSingleLine(logger, "logger");
        message = requireSingleLine(message, "message");
        Objects.requireNonNull(correlationId, "correlationId");
        traceId = requireMatch(traceId, TRACE_ID, "traceId");
        spanId = requireMatch(spanId, SPAN_ID, "spanId");
        Objects.requireNonNull(role, "role");
        module = requireMatch(module, MODULE, "module");
        slice = requireMatch(slice, SLICE, "slice");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventCode, "eventCode");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(durationMs, "durationMs");
        Objects.requireNonNull(dbQueryCount, "dbQueryCount");
        Objects.requireNonNull(error, "error");
        if (actorType.isPresent() != actorId.isPresent()) {
            throw new IllegalArgumentException("actorType and actorId must be present together");
        }
        errorCode.ifPresent(code -> requireMatch(code, ERROR_CODE, "errorCode"));
        durationMs.ifPresent(value -> requireNonNegative(value, "durationMs"));
        dbQueryCount.ifPresent(value -> requireNonNegative(value, "dbQueryCount"));
        if (durationMs.isPresent() != dbQueryCount.isPresent()) {
            throw new IllegalArgumentException(
                    "durationMs and dbQueryCount must be present together");
        }
        if (error.isPresent() && errorCode.isEmpty()) {
            throw new IllegalArgumentException("errorCode is required with an error stack");
        }
    }

    public enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    public enum RuntimeRole {
        API,
        WORKER,
        PINDIST
    }

    public record StructuredError(List<StackFrame> stack, boolean truncated) {

        public StructuredError {
            stack = List.copyOf(Objects.requireNonNull(stack, "stack"));
            if (stack.isEmpty()) {
                throw new IllegalArgumentException("error stack must not be empty");
            }
        }
    }

    public record StackFrame(String declaringClass, String method, String file, int line) {

        public StackFrame {
            declaringClass = requireSingleLine(declaringClass, "declaringClass");
            method = requireSingleLine(method, "method");
            file = requireSingleLine(file, "file");
            if (line < -1) {
                throw new IllegalArgumentException("line must be -1 or greater");
            }
        }
    }

    private static String requireSingleLine(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank single-line text");
        }
        return value;
    }

    private static String requireMatch(String value, Pattern pattern, String name) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid syntax");
        }
        return value;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
