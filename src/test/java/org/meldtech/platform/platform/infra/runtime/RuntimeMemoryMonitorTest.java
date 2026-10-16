package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import reactor.test.StepVerifier;

class RuntimeMemoryMonitorTest {
    @Test
    void lifecycleSamplesImmediatelyAndFailsClosedOnMeasurementError(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("memory.max"), "1073741824");
        Files.writeString(root.resolve("memory.current"), "0");
        var meters = new SimpleMeterRegistry();
        try (var context = new GenericApplicationContext()) {
            var broken = new CountDownLatch(1);
            context.addApplicationListener(
                    event -> {
                        if (event instanceof AvailabilityChangeEvent<?> change
                                && change.getState() == LivenessState.BROKEN) {
                            broken.countDown();
                        }
                    });
            context.refresh();
            var environment =
                    new MockEnvironment().withProperty("cbt.runtime.cgroup-root", root.toString());
            var drain = new RuntimeDrainState(context, environment);
            var monitor = new RuntimeMemoryMonitor(environment, drain, context, meters);
            try {
                Files.writeString(root.resolve("memory.current"), "invalid");
                assertThat(monitor.isRunning()).isFalse();
                monitor.start();
                assertThat(monitor.isRunning()).isTrue();
                assertThat(broken.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(drain.isDraining()).isTrue();
                monitor.stop();
                assertThat(monitor.isRunning()).isFalse();
                monitor.start();
                assertThat(monitor.isRunning()).isFalse();
            } finally {
                monitor.stop();
                drain.destroy();
                meters.close();
            }
        }
    }

    @Test
    void latchingFailsProbesDrainsAdmissionAndRecordsOneRestart(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("memory.max"), "1073741824");
        Files.writeString(root.resolve("memory.current"), "0");
        var meters = new SimpleMeterRegistry();
        try (var context = new GenericApplicationContext()) {
            var states = new ArrayList<Object>();
            context.addApplicationListener(
                    event -> {
                        if (event instanceof AvailabilityChangeEvent<?> change) {
                            states.add(change.getState());
                        }
                    });
            context.refresh();
            var environment =
                    new MockEnvironment().withProperty("cbt.runtime.cgroup-root", root.toString());
            var drain = new RuntimeDrainState(context, environment);
            var monitor = new RuntimeMemoryMonitor(environment, drain, context, meters);
            try {
                monitor.latch(MemoryPressurePolicy.Reason.HEAP);
                monitor.latch(MemoryPressurePolicy.Reason.NONE);
                monitor.latch(MemoryPressurePolicy.Reason.CONTAINER);
                assertThat(drain.isDraining()).isTrue();
                assertThat(states)
                        .containsExactly(ReadinessState.REFUSING_TRAFFIC, LivenessState.BROKEN);
                StepVerifier.create(monitor.health())
                        .assertNext(
                                health -> {
                                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                                    assertThat(health.getDetails()).isEmpty();
                                })
                        .verifyComplete();
                assertThat(
                                meters.get("runtime_heap_threshold_restart_total")
                                        .tag("reason", "heap")
                                        .counter()
                                        .count())
                        .isEqualTo(1);
            } finally {
                monitor.stop();
                drain.destroy();
                meters.close();
            }
        }
    }

    @Test
    void missingOrUnlimitedContainerInputFailsClosed(@TempDir Path root) throws Exception {
        assertThatThrownBy(() -> new ContainerMemory(root)).isInstanceOf(java.io.IOException.class);
        Files.writeString(root.resolve("memory.max"), "max");
        assertThatThrownBy(() -> new ContainerMemory(root))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsV1AndV2LimitsWithoutHostFallback(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve("memory"));
        Files.writeString(root.resolve("memory/memory.limit_in_bytes"), "1073741824");
        Files.writeString(root.resolve("memory/memory.usage_in_bytes"), "123");
        assertThat(new ContainerMemory(root).used()).isEqualTo(123);
        Files.writeString(root.resolve("memory.max"), "1073741824");
        Files.writeString(root.resolve("memory.current"), "456");
        assertThat(new ContainerMemory(root).used()).isEqualTo(456);
    }
}
