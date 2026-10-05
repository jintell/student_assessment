package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

final class OtlpTlsMaterial implements AutoCloseable {

    private final byte[] trustCertificates;
    private final byte[] clientCertificate;
    private final byte[] clientPrivateKey;

    private OtlpTlsMaterial(
            byte[] trustCertificates, byte[] clientCertificate, byte[] clientPrivateKey) {
        this.trustCertificates = trustCertificates;
        this.clientCertificate = clientCertificate;
        this.clientPrivateKey = clientPrivateKey;
    }

    static OtlpTlsMaterial load(ObservabilityProperties.Tls configuration) {
        Objects.requireNonNull(configuration, "configuration");
        byte[] trust = null;
        byte[] certificate = null;
        byte[] privateKey = null;
        try {
            trust = read(configuration.trustCertificateReference());
            certificate = read(configuration.clientCertificateReference());
            privateKey = read(configuration.clientPrivateKeyReference());
            return new OtlpTlsMaterial(trust, certificate, privateKey);
        } catch (IOException | InvalidPathException | SecurityException exception) {
            if (trust != null) {
                clear(trust);
            }
            if (certificate != null) {
                clear(certificate);
            }
            if (privateKey != null) {
                clear(privateKey);
            }
            throw new IllegalStateException("OTLP TLS material is unavailable");
        }
    }

    void configure(OtlpGrpcSpanExporterBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        builder.setTrustedCertificates(trustCertificates);
        builder.setClientTls(clientPrivateKey, clientCertificate);
    }

    void configure(OtlpGrpcMetricExporterBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        builder.setTrustedCertificates(trustCertificates);
        builder.setClientTls(clientPrivateKey, clientCertificate);
    }

    void configure(OtlpGrpcLogRecordExporterBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        builder.setTrustedCertificates(trustCertificates);
        builder.setClientTls(clientPrivateKey, clientCertificate);
    }

    @Override
    public void close() {
        clear(trustCertificates);
        clear(clientCertificate);
        clear(clientPrivateKey);
    }

    private static byte[] read(String reference) throws IOException {
        Path path = Path.of(Objects.requireNonNull(reference, "TLS material reference"));
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("TLS material is not readable");
        }
        return Files.readAllBytes(path);
    }

    private static void clear(byte[] value) {
        Arrays.fill(value, (byte) 0);
    }
}
