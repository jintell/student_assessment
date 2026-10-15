package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meldtech.platform.platform.api.RuntimeRole;

class RuntimeTelemetryConfigurationTest {
    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void allMetricsAndOtlpResourcesCarryTheRoleWithoutReplacingDatabaseRole(RuntimeRole role) {
        var resource = new ObservabilityProperties.Resource("cbt-platform", "test", role.profile());
        var properties = mock(ObservabilityProperties.class);
        when(properties.resource()).thenReturn(resource);
        var registry = new SimpleMeterRegistry();
        try {
            new RuntimeTelemetryConfiguration()
                    .runtimeRoleMetrics(role, properties)
                    .customize(registry);
            var counter =
                    registry.counter("database.test", "role", "app_api", "runtime_role", "wrong");
            assertThat(counter.getId().getTag("runtime_role")).isEqualTo(role.profile());
            assertThat(counter.getId().getTag("role")).isEqualTo("app_api");
            assertThat(
                            OpenTelemetryTracerConfiguration.resource(resource)
                                    .getAttribute(OpenTelemetryTracerConfiguration.RUNTIME_ROLE))
                    .isEqualTo(role.profile());
        } finally {
            registry.close();
        }
    }

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void disagreementWithConfiguredTelemetryRoleFailsStartup(RuntimeRole role) {
        var properties = mock(ObservabilityProperties.class);
        String other = role == RuntimeRole.API ? "worker" : "api";
        when(properties.resource())
                .thenReturn(new ObservabilityProperties.Resource("cbt-platform", "test", other));
        assertThatThrownBy(
                        () ->
                                new RuntimeTelemetryConfiguration()
                                        .runtimeRoleMetrics(role, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUNTIME_TELEMETRY_ROLE_MISMATCH");
    }
}
