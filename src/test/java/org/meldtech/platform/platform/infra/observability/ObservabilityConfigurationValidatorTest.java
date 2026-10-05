package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObservabilityConfigurationValidatorTest {

    @Test
    void acceptsTheCompleteApprovedConfiguration() {
        assertThatNoException().isThrownBy(() -> validator(validProperties()).validate());
    }

    @Test
    void rejectsAnAbsentCollectorEndpoint() {
        assertThatThrownBy(() -> validator(withoutCollectorEndpoint()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OBSERVABILITY_CONFIGURATION_INVALID")
                .hasMessageContaining("collector.endpoint")
                .hasMessageNotContaining("/run/secrets");
    }

    @Test
    void rejectsAnOutOfRangeSamplingRatio() {
        assertThatThrownBy(
                        () ->
                                validator(
                                                properties(
                                                        URI.create("https://collector:4317"),
                                                        0.5d,
                                                        Set.of("policy_key"),
                                                        "/run/secrets/observability/hash"))
                                        .validate())
                .hasMessageContaining("sampling.standard-export-ratio");
    }

    @Test
    void rejectsAnAbsentRedactionAllowlist() {
        assertThatThrownBy(
                        () ->
                                validator(
                                                properties(
                                                        URI.create("https://collector:4317"),
                                                        1.0d,
                                                        Set.of(),
                                                        "/run/secrets/observability/hash"))
                                        .validate())
                .hasMessageContaining("redaction.permitted-key-fields");
    }

    @Test
    void rejectsAnAbsentCandidateHashReference() {
        assertThatThrownBy(
                        () ->
                                validator(
                                                properties(
                                                        URI.create("https://collector:4317"),
                                                        1.0d,
                                                        Set.of("policy_key"),
                                                        " "))
                                        .validate())
                .hasMessageContaining("candidate-hash.secret-reference");
    }

    private static ObservabilityConfigurationValidator validator(
            ObservabilityProperties properties) {
        return new ObservabilityConfigurationValidator(properties, false);
    }

    private static ObservabilityProperties validProperties() {
        return properties(
                URI.create("https://collector:4317"),
                1.0d,
                Set.of("policy_key"),
                "/run/secrets/observability/hash");
    }

    @SuppressWarnings("NullAway")
    private static ObservabilityProperties withoutCollectorEndpoint() {
        return properties(null, 1.0d, Set.of("policy_key"), "/run/secrets/observability/hash");
    }

    private static ObservabilityProperties properties(
            URI endpoint, double standardExportRatio, Set<String> allowlist, String hashReference) {
        URI signalEndpoint = URI.create("https://collector:4317");
        ObservabilityProperties.Queue queue =
                new ObservabilityProperties.Queue(256, 64, Duration.ofSeconds(30));
        return new ObservabilityProperties(
                new ObservabilityProperties.Resource("cbt-platform", "test", "api"),
                new ObservabilityProperties.Collector(
                        endpoint,
                        ObservabilityProperties.Protocol.GRPC,
                        new ObservabilityProperties.Tls(
                                true,
                                "/run/secrets/observability/ca",
                                "/run/secrets/observability/cert",
                                "/run/secrets/observability/key"),
                        new ObservabilityProperties.SignalEndpoints(
                                signalEndpoint, signalEndpoint, signalEndpoint)),
                new ObservabilityProperties.Sampling(1.0d, 1.0d, standardExportRatio, 0.10d),
                new ObservabilityProperties.Redaction(allowlist),
                new ObservabilityProperties.CandidateHash(hashReference),
                new ObservabilityProperties.Export(
                        Duration.ofSeconds(2),
                        new ObservabilityProperties.SignalQueues(queue, queue, queue)),
                new ObservabilityProperties.Trace(Duration.ofHours(1)),
                new ObservabilityProperties.MetricCatalogues(
                        Map.of(
                                "active-sessions", 500,
                                "replicas", 12,
                                "queues", 32,
                                "roles", 14,
                                "policies", 64,
                                "shards", 32,
                                "routes", 100,
                                "slices", 160)));
    }
}
