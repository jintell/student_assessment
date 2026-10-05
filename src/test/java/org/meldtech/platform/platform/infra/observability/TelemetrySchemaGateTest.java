package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.platform.academic.domain.telemetryfixture.CandidateRecord;

class TelemetrySchemaGateTest {

    @Test
    void acceptsTheStructuredLoggingSchema() throws ClassNotFoundException {
        Class<?> loggingType =
                Class.forName(
                        "org.meldtech.platform.platform.infra.observability.StructuredLogEvent");

        assertThatNoException()
                .isThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(loggingType));
    }

    @Test
    void rejectsSecretDomainAndUnboundedLoggingFields() {
        assertThatThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(SecretLogBoundary.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("authorizationToken");
        assertThatThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(DomainLogBoundary.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("exposes domain type");
        assertThatThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(MapLogBoundary.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("unapproved telemetry container");
    }

    @Test
    void acceptsRegisteredSpanAttributesAndMetricLabels() {
        assertThatNoException().isThrownBy(TelemetrySchemaGate::verifySpanAttributes);
        assertThatNoException()
                .isThrownBy(
                        () ->
                                TelemetrySchemaGate.verifyMetricLabels(
                                        Path.of("config/observability/metric-cardinality.json")));
    }

    @Test
    void rejectsSecretsAndPersonalDataFromSpanAndMetricFields(@TempDir Path directory)
            throws IOException {
        assertThatThrownBy(
                        () ->
                                TelemetrySchemaGate.verifyFieldNames(
                                        "span attribute",
                                        List.of("authorizationToken", "candidateEmail")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("span attribute");

        Path metricContract = directory.resolve("metric-cardinality.json");
        Files.writeString(
                metricContract,
                """
                {"metrics":[{"name":"unsafe_total","labels":["answerContent"]}]}
                """);

        assertThatThrownBy(() -> TelemetrySchemaGate.verifyMetricLabels(metricContract))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("metric label answerContent");
    }

    private record SecretLogBoundary(String authorizationToken) {}

    private record DomainLogBoundary(CandidateRecord candidate) {}

    private record MapLogBoundary(Map<String, String> fields) {}
}
