package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ObservabilityPropertiesTest {

    @Test
    void bindsTheCompleteTwelveFactorConfigurationSurface() {
        Map<String, Object> values =
                Map.ofEntries(
                        Map.entry("cbt.observability.resource.service-name", "cbt-platform"),
                        Map.entry("cbt.observability.resource.environment", "test"),
                        Map.entry("cbt.observability.resource.role", "api"),
                        Map.entry("cbt.observability.collector.endpoint", "https://collector:4317"),
                        Map.entry("cbt.observability.collector.protocol", "grpc"),
                        Map.entry("cbt.observability.collector.tls.enabled", "true"),
                        Map.entry(
                                "cbt.observability.collector.tls.trust-certificate-reference",
                                "/run/secrets/observability/ca.pem"),
                        Map.entry(
                                "cbt.observability.collector.tls.client-certificate-reference",
                                "/run/secrets/observability/tls.crt"),
                        Map.entry(
                                "cbt.observability.collector.tls.client-private-key-reference",
                                "/run/secrets/observability/tls.key"),
                        Map.entry(
                                "cbt.observability.collector.endpoints.traces",
                                "https://traces:4317"),
                        Map.entry(
                                "cbt.observability.collector.endpoints.metrics",
                                "https://metrics:4317"),
                        Map.entry(
                                "cbt.observability.collector.endpoints.logs", "https://logs:4317"),
                        Map.entry("cbt.observability.sampling.exam-entry-head-ratio", "1.0"),
                        Map.entry("cbt.observability.sampling.grading-head-ratio", "1.0"),
                        Map.entry("cbt.observability.sampling.standard-export-ratio", "1.0"),
                        Map.entry("cbt.observability.sampling.standard-tail-ratio", "0.10"),
                        Map.entry(
                                "cbt.observability.redaction.permitted-key-fields[0]",
                                "policy_key"),
                        Map.entry(
                                "cbt.observability.candidate-hash.secret-reference",
                                "/run/secrets/observability/candidate-hash-salt"),
                        Map.entry("cbt.observability.export.timeout", "2s"),
                        Map.entry("cbt.observability.export.queues.traces.capacity", "1024"),
                        Map.entry("cbt.observability.export.queues.traces.batch-size", "128"),
                        Map.entry("cbt.observability.export.queues.traces.item-max-age", "30s"),
                        Map.entry("cbt.observability.export.queues.metrics.capacity", "1024"),
                        Map.entry("cbt.observability.export.queues.metrics.batch-size", "128"),
                        Map.entry("cbt.observability.export.queues.metrics.item-max-age", "30s"),
                        Map.entry("cbt.observability.export.queues.logs.capacity", "2048"),
                        Map.entry("cbt.observability.export.queues.logs.batch-size", "256"),
                        Map.entry("cbt.observability.export.queues.logs.item-max-age", "30s"),
                        Map.entry("cbt.observability.trace.max-continuation-age", "1h"),
                        Map.entry(
                                "cbt.observability.metric-catalogues.ceilings.active-sessions",
                                "500"),
                        Map.entry("cbt.observability.metric-catalogues.ceilings.replicas", "12"));

        ObservabilityProperties properties =
                new Binder(new MapConfigurationPropertySource(values))
                        .bind("cbt.observability", Bindable.of(ObservabilityProperties.class))
                        .orElseThrow(
                                () -> new AssertionError("Observability properties did not bind"));

        assertThat(properties.resource())
                .isEqualTo(new ObservabilityProperties.Resource("cbt-platform", "test", "api"));
        assertThat(properties.collector().endpoint())
                .isEqualTo(URI.create("https://collector:4317"));
        assertThat(properties.collector().protocol())
                .isEqualTo(ObservabilityProperties.Protocol.GRPC);
        assertThat(properties.collector().tls().enabled()).isTrue();
        assertThat(properties.collector().tls().clientCertificateReference())
                .isEqualTo("/run/secrets/observability/tls.crt");
        assertThat(properties.collector().tls().clientPrivateKeyReference())
                .isEqualTo("/run/secrets/observability/tls.key");
        assertThat(properties.sampling().standardTailRatio()).isEqualTo(0.10d);
        assertThat(properties.redaction().permittedKeyFields()).isEqualTo(Set.of("policy_key"));
        assertThat(properties.export().timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.export().queues().logs().capacity()).isEqualTo(2048);
        assertThat(properties.trace().maxContinuationAge()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.metricCatalogues().ceilings())
                .containsEntry("active-sessions", 500)
                .containsEntry("replicas", 12);
    }
}
