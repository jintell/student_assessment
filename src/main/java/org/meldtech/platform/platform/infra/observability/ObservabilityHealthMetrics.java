package org.meldtech.platform.platform.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

final class ObservabilityHealthMetrics implements TelemetryHealth {

    private final Map<Signal, Counter> attempts;
    private final Map<Signal, Counter> successes;
    private final Map<Signal, Counter> timeouts;
    private final Map<Signal, Map<DropReason, Counter>> drops;
    private final Map<Signal, AtomicInteger> queueDepths;
    private final Map<Surface, Map<RedactionReason, Counter>> redactionRejections;

    ObservabilityHealthMetrics(MeterRegistry registry, ObservabilityProperties properties) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(properties, "properties");
        attempts = signalCounters(registry, "telemetry_export_attempt_total");
        successes = signalCounters(registry, "telemetry_export_success_total");
        timeouts = signalCounters(registry, "telemetry_export_timeout_total");
        drops = dropCounters(registry);
        queueDepths = queueGauges(registry, properties.export().queues());
        redactionRejections = redactionCounters(registry);
    }

    @Override
    public void exportAttempt(Signal signal) {
        required(attempts, signal).increment();
    }

    @Override
    public void exportSuccess(Signal signal) {
        required(successes, signal).increment();
    }

    @Override
    public void exportTimeout(Signal signal) {
        required(timeouts, signal).increment();
    }

    @Override
    public void dropped(Signal signal, DropReason reason, int count) {
        if (count < 1) {
            return;
        }
        required(required(drops, signal), reason).increment(count);
    }

    @Override
    public void queueDepth(Signal signal, int depth) {
        required(queueDepths, signal).set(Math.max(depth, 0));
    }

    @Override
    public void redactionRejected(Surface surface, RedactionReason reason) {
        required(required(redactionRejections, surface), reason).increment();
    }

    private static Map<Signal, Counter> signalCounters(MeterRegistry registry, String name) {
        EnumMap<Signal, Counter> counters = new EnumMap<>(Signal.class);
        for (Signal signal : Signal.values()) {
            counters.put(
                    signal, Counter.builder(name).tag("signal", tag(signal)).register(registry));
        }
        return Map.copyOf(counters);
    }

    private static Map<Signal, Map<DropReason, Counter>> dropCounters(MeterRegistry registry) {
        EnumMap<Signal, Map<DropReason, Counter>> counters = new EnumMap<>(Signal.class);
        for (Signal signal : Signal.values()) {
            EnumMap<DropReason, Counter> reasons = new EnumMap<>(DropReason.class);
            for (DropReason reason : DropReason.values()) {
                reasons.put(
                        reason,
                        Counter.builder(signal.dropMetric())
                                .tag("reason", tag(reason))
                                .register(registry));
            }
            counters.put(signal, Map.copyOf(reasons));
        }
        return Map.copyOf(counters);
    }

    private static Map<Signal, AtomicInteger> queueGauges(
            MeterRegistry registry, ObservabilityProperties.SignalQueues queues) {
        EnumMap<Signal, AtomicInteger> depths = new EnumMap<>(Signal.class);
        for (Signal signal : Signal.values()) {
            AtomicInteger depth = new AtomicInteger();
            depths.put(signal, depth);
            Gauge.builder("telemetry_export_queue_depth", depth, AtomicInteger::doubleValue)
                    .tag("signal", tag(signal))
                    .register(registry);
            Gauge.builder(
                            "telemetry_export_queue_capacity",
                            signal,
                            ignored -> capacity(signal, queues))
                    .tag("signal", tag(signal))
                    .register(registry);
        }
        return Map.copyOf(depths);
    }

    private static Map<Surface, Map<RedactionReason, Counter>> redactionCounters(
            MeterRegistry registry) {
        EnumMap<Surface, Map<RedactionReason, Counter>> counters = new EnumMap<>(Surface.class);
        for (Surface surface : Surface.values()) {
            EnumMap<RedactionReason, Counter> reasons = new EnumMap<>(RedactionReason.class);
            for (RedactionReason reason : RedactionReason.values()) {
                reasons.put(
                        reason,
                        Counter.builder("telemetry_redaction_rejection_total")
                                .tag("surface", tag(surface))
                                .tag("reason", tag(reason))
                                .register(registry));
            }
            counters.put(surface, Map.copyOf(reasons));
        }
        return Map.copyOf(counters);
    }

    private static int capacity(
            Signal signal, ObservabilityProperties.SignalQueues configurations) {
        return switch (signal) {
            case TRACE -> configurations.traces().capacity();
            case METRIC -> configurations.metrics().capacity();
            case LOG -> configurations.logs().capacity();
        };
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static <K, V> V required(Map<K, V> values, K key) {
        return Objects.requireNonNull(values.get(key), "registered telemetry dimension");
    }

    enum Signal {
        TRACE("telemetry_span_dropped_total"),
        METRIC("telemetry_metric_point_dropped_total"),
        LOG("telemetry_log_event_dropped_total");

        private final String dropMetric;

        Signal(String dropMetric) {
            this.dropMetric = dropMetric;
        }

        String dropMetric() {
            return dropMetric;
        }
    }

    enum DropReason {
        OVERFLOW,
        EXPIRY,
        REJECTION,
        EXPORT_FAILURE
    }

    enum Surface {
        LOG,
        SPAN,
        METRIC,
        EVENT
    }

    enum RedactionReason {
        PROHIBITED_FIELD,
        SERIALIZATION_FAILURE
    }
}
