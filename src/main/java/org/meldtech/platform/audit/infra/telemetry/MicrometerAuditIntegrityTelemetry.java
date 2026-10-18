package org.meldtech.platform.audit.infra.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.meldtech.platform.audit.application.AuditEpochSealingJob.JobResult;
import org.meldtech.platform.audit.application.AuditIntegrityTelemetry;

final class MicrometerAuditIntegrityTelemetry implements AuditIntegrityTelemetry {

    private final Counter emissionFailures;
    private final Counter casRetries;
    private final Counter rootForks;
    private final Map<JobResult, Counter> seals = new EnumMap<>(JobResult.class);
    private final Map<DispositionOutcome, Counter> dispositions =
            new EnumMap<>(DispositionOutcome.class);
    private final AtomicReference<Double> verification = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> overdue = new AtomicReference<>(Double.NaN);
    private final AtomicReference<SequenceRange> rootSequences = new AtomicReference<>();

    MicrometerAuditIntegrityTelemetry(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        emissionFailures =
                Counter.builder("audit.emit.failure")
                        .description("Audit emission failures propagated to the caller")
                        .register(registry);
        casRetries =
                Counter.builder("audit.epoch.seal.cas.retry")
                        .description("Lost root CAS attempts that trigger recomputation")
                        .register(registry);
        rootForks =
                Counter.builder("audit.root.chain.fork.detected")
                        .description(
                                "Observed root forks; zero alone does not establish verifier health")
                        .register(registry);
        Gauge.builder("audit.chain.verification.result", verification, AtomicReference::get)
                .description("Last complete verification: 1 success, 0 failure, NaN unknown")
                .register(registry);
        Gauge.builder("audit.checkpoint.overdue.count", overdue, AtomicReference::get)
                .description("Overdue checkpoints in a complete inventory; NaN until sampled")
                .register(registry);
        for (JobResult result : JobResult.values()) {
            seals.put(
                    result,
                    Counter.builder("audit.epoch.seal")
                            .description("Completed seal attempts by bounded result")
                            .tag("result", result.name())
                            .register(registry));
        }
        for (DispositionOutcome outcome : DispositionOutcome.values()) {
            dispositions.put(
                    outcome,
                    Counter.builder("audit.epoch.disposition")
                            .description("Observed disposition transitions by bounded outcome")
                            .tag("outcome", outcome.name())
                            .register(registry));
        }
        for (String statistic : new String[] {"min", "max"}) {
            Gauge.builder(
                            "audit.root.chain.seq",
                            rootSequences,
                            reference -> {
                                SequenceRange range = reference.get();
                                if (range == null) {
                                    return Double.NaN;
                                }
                                return statistic.equals("min") ? range.minimum() : range.maximum();
                            })
                    .description("Root sequence range of a complete inventory; never a tenant sum")
                    .tag("statistic", statistic)
                    .register(registry);
        }
    }

    @Override
    public void verificationCompleted(boolean successful) {
        verification.set(successful ? 1.0 : 0.0);
    }

    @Override
    public void emissionFailed() {
        emissionFailures.increment();
    }

    @Override
    public void checkpointOverdueCount(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("checkpoint count must be non-negative");
        }
        overdue.set((double) count);
    }

    @Override
    public void sealCompleted(JobResult result) {
        Objects.requireNonNull(seals.get(result), "seal result counter").increment();
    }

    @Override
    public void dispositionCompleted(DispositionOutcome outcome) {
        Objects.requireNonNull(dispositions.get(outcome), "disposition outcome counter")
                .increment();
    }

    @Override
    public void compareAndSwapRetry() {
        casRetries.increment();
    }

    @Override
    public void rootForkDetected() {
        rootForks.increment();
    }

    @Override
    public void rootSequenceRange(long minimum, long maximum) {
        if (minimum < 0 || maximum < minimum) {
            throw new IllegalArgumentException(
                    "root sequence range must be ordered and non-negative");
        }
        rootSequences.set(new SequenceRange(minimum, maximum));
    }

    @Override
    public void invalidateSamples() {
        verification.set(Double.NaN);
        overdue.set(Double.NaN);
        rootSequences.set(null);
    }

    private record SequenceRange(long minimum, long maximum) {}
}
