package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OtlpTlsMaterialTest {

    @Test
    void appliesProjectedWorkloadIdentityToEverySignalExporter(@TempDir Path directory)
            throws IOException {
        byte[] trust = "trust-pem".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] certificate =
                "client-certificate-pem".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] privateKey =
                "client-private-key-pem".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ObservabilityProperties.Tls configuration =
                configuration(directory, trust, certificate, privateKey);
        OtlpGrpcSpanExporterBuilder spans = mock(OtlpGrpcSpanExporterBuilder.class);
        OtlpGrpcMetricExporterBuilder metrics = mock(OtlpGrpcMetricExporterBuilder.class);
        OtlpGrpcLogRecordExporterBuilder logs = mock(OtlpGrpcLogRecordExporterBuilder.class);

        try (OtlpTlsMaterial material = OtlpTlsMaterial.load(configuration)) {
            material.configure(spans);
            material.configure(metrics);
            material.configure(logs);

            verify(spans).setTrustedCertificates(aryEq(trust));
            verify(spans).setClientTls(aryEq(privateKey), aryEq(certificate));
            verify(metrics).setTrustedCertificates(aryEq(trust));
            verify(metrics).setClientTls(aryEq(privateKey), aryEq(certificate));
            verify(logs).setTrustedCertificates(aryEq(trust));
            verify(logs).setClientTls(aryEq(privateKey), aryEq(certificate));
        }
    }

    @Test
    void failureDoesNotDiscloseAWorkloadCredentialReference(@TempDir Path directory) {
        Path missing = directory.resolve("sensitive-workload-key-name");
        ObservabilityProperties.Tls configuration =
                new ObservabilityProperties.Tls(
                        true, missing.toString(), missing.toString(), missing.toString());

        assertThatThrownBy(() -> OtlpTlsMaterial.load(configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OTLP TLS material is unavailable")
                .hasMessageNotContaining(missing.toString())
                .hasNoCause();
    }

    private static ObservabilityProperties.Tls configuration(
            Path directory, byte[] trust, byte[] certificate, byte[] privateKey)
            throws IOException {
        Path trustPath = Files.write(directory.resolve("trust.pem"), trust);
        Path certificatePath = Files.write(directory.resolve("certificate.pem"), certificate);
        Path privateKeyPath = Files.write(directory.resolve("private-key.pem"), privateKey);
        return new ObservabilityProperties.Tls(
                true, trustPath.toString(), certificatePath.toString(), privateKeyPath.toString());
    }
}
