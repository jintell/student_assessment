package org.meldtech.platform.platform.infra.kernel.error;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;

final class MicrometerProblemDetailMetrics implements ProblemDetailMetrics {

    private static final String EMITTED_METRIC = "problem_detail_emitted_total";
    private static final String UNMAPPED_METRIC = "problem_detail_unmapped_total";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> emitted = new ConcurrentHashMap<>();
    private final ConcurrentMap<FallbackReason, Counter> unmapped = new ConcurrentHashMap<>();

    MicrometerProblemDetailMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void emitted(String code) {
        Objects.requireNonNull(code, "code");
        emitted.computeIfAbsent(
                        code,
                        value ->
                                Counter.builder(EMITTED_METRIC)
                                        .tag("code", value)
                                        .register(registry))
                .increment();
    }

    @Override
    public void fallback(FallbackReason reason) {
        Objects.requireNonNull(reason, "reason");
        unmapped.computeIfAbsent(
                        reason,
                        value ->
                                Counter.builder(UNMAPPED_METRIC)
                                        .tag("reason", value.name().toLowerCase(Locale.ROOT))
                                        .register(registry))
                .increment();
    }
}
