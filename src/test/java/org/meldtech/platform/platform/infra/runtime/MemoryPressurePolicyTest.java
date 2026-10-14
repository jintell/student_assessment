package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryPressurePolicyTest {
    @Test
    void heapMustRemainAboveThresholdAndRestartLatches() {
        var policy = new MemoryPressurePolicy();
        assertThat(sample(policy, 0, 85, 0)).isEqualTo(MemoryPressurePolicy.Reason.NONE);
        assertThat(sample(policy, 59, 85, 0)).isEqualTo(MemoryPressurePolicy.Reason.NONE);
        assertThat(sample(policy, 60, 84, 0)).isEqualTo(MemoryPressurePolicy.Reason.NONE);
        sample(policy, 61, 85, 0);
        assertThat(sample(policy, 120, 85, 0)).isEqualTo(MemoryPressurePolicy.Reason.NONE);
        assertThat(sample(policy, 121, 85, 0)).isEqualTo(MemoryPressurePolicy.Reason.HEAP);
        assertThat(sample(policy, 122, 0, 0)).isEqualTo(MemoryPressurePolicy.Reason.HEAP);
    }

    @Test
    void nativePressureHasItsOwnContinuousWindow() {
        var policy = new MemoryPressurePolicy();
        sample(policy, 0, 20, 90);
        assertThat(sample(policy, 9, 20, 90)).isEqualTo(MemoryPressurePolicy.Reason.NONE);
        sample(policy, 10, 20, 89);
        sample(policy, 11, 20, 90);
        assertThat(sample(policy, 21, 20, 90)).isEqualTo(MemoryPressurePolicy.Reason.CONTAINER);
        assertThat(sample(policy, 22, 0, 0)).isEqualTo(MemoryPressurePolicy.Reason.CONTAINER);
    }

    @Test
    void capsRequireExplicitBoundsAndNativeHeadroom() {
        var caps = List.of("-Xmx512m", "-XX:MaxDirectMemorySize=128m");
        ContainerMemory.validateCaps(caps, 512L << 20, 1024L << 20);
        assertThatThrownBy(() -> ContainerMemory.validateCaps(caps, 512L << 20, 640L << 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ContainerMemory.validateCaps(
                                        List.of("-Xmx512m"), 512L << 20, 1024L << 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ContainerMemory.validateCaps(
                                        List.of(
                                                "-Xmx512m",
                                                "-XX:MaxDirectMemorySize=128m",
                                                "-XX:MaxRAMPercentage=50"),
                                        512L << 20,
                                        1024L << 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MemoryPressurePolicy.Reason sample(
            MemoryPressurePolicy policy, long seconds, long heap, long used) {
        return policy.sample(Duration.ofSeconds(seconds).toNanos(), heap, 100, used, 100);
    }
}
