package org.meldtech.platform.platform.infra.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Operational sampling on all roles, not a business sweep or API workload scheduler. */
@Component("runtimeMemory")
final class RuntimeMemoryMonitor implements SmartLifecycle, ReactiveHealthIndicator {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeMemoryMonitor.class);
    private final MemoryPressurePolicy policy = new MemoryPressurePolicy();
    private final ContainerMemory memory;
    private final RuntimeDrainState drain;
    private final ApplicationContext context;
    private final MeterRegistry meters;
    private final Scheduler sampler = Schedulers.newBoundedElastic(1, 1, "runtime-memory");
    private final long origin;
    private volatile MemoryPressurePolicy.Reason reason = MemoryPressurePolicy.Reason.NONE;
    private @Nullable Disposable subscription;

    RuntimeMemoryMonitor(
            Environment environment,
            RuntimeDrainState drain,
            ApplicationContext context,
            MeterRegistry meters)
            throws IOException {
        this.memory =
                new ContainerMemory(
                        Path.of(
                                environment.getProperty(
                                        "cbt.runtime.cgroup-root", "/sys/fs/cgroup")));
        this.drain = drain;
        this.context = context;
        this.meters = meters;
        this.origin = meters.config().clock().monotonicTime();
    }

    @Override
    public synchronized void start() {
        if (subscription == null) {
            subscription =
                    RuntimeTickSources.memory(sampler)
                            .subscribe(
                                    ignored -> sample(),
                                    failure ->
                                            latch(MemoryPressurePolicy.Reason.MEASUREMENT_FAILED));
        }
    }

    private void sample() {
        try {
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            latch(
                    policy.sample(
                            meters.config().clock().monotonicTime() - origin,
                            heap.getUsed(),
                            heap.getMax(),
                            memory.used(),
                            memory.limit()));
        } catch (IOException | RuntimeException failure) {
            latch(MemoryPressurePolicy.Reason.MEASUREMENT_FAILED);
        }
    }

    synchronized void latch(MemoryPressurePolicy.Reason next) {
        if (reason == MemoryPressurePolicy.Reason.NONE
                && next != MemoryPressurePolicy.Reason.NONE) {
            reason = next;
            drain.requestDrain();
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
            meters.counter(
                            "runtime_heap_threshold_restart_total",
                            "reason",
                            next.name().toLowerCase(java.util.Locale.ROOT))
                    .increment();
            LOGGER.error("Runtime memory restart requested: reason={}", next);
        }
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(
                () ->
                        reason == MemoryPressurePolicy.Reason.NONE
                                ? Health.up().build()
                                : Health.down().build());
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
        sampler.dispose();
    }

    @Override
    public synchronized boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }
}
