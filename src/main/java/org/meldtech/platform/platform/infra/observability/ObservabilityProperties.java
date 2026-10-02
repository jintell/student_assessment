package org.meldtech.platform.platform.infra.observability;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cbt.observability")
record ObservabilityProperties(
        Resource resource,
        Collector collector,
        Sampling sampling,
        Redaction redaction,
        CandidateHash candidateHash,
        Export export,
        Trace trace,
        MetricCatalogues metricCatalogues) {

    record Resource(String serviceName, String environment, String role) {}

    record Collector(URI endpoint, Protocol protocol, Tls tls, SignalEndpoints endpoints) {}

    enum Protocol {
        GRPC
    }

    record Tls(
            Boolean enabled,
            String trustCertificateReference,
            String clientCertificateReference,
            String clientPrivateKeyReference) {}

    record SignalEndpoints(URI traces, URI metrics, URI logs) {}

    record Sampling(
            Double examEntryHeadRatio,
            Double gradingHeadRatio,
            Double standardExportRatio,
            Double standardTailRatio) {}

    record Redaction(Set<String> permittedKeyFields) {
        Redaction {
            permittedKeyFields = permittedKeyFields == null ? null : Set.copyOf(permittedKeyFields);
        }
    }

    record CandidateHash(String secretReference) {}

    record Export(Duration timeout, SignalQueues queues) {}

    record SignalQueues(Queue traces, Queue metrics, Queue logs) {}

    record Queue(Integer capacity, Integer batchSize, Duration itemMaxAge) {}

    record Trace(Duration maxContinuationAge) {}

    record MetricCatalogues(Map<String, Integer> ceilings) {
        MetricCatalogues {
            ceilings = ceilings == null ? null : Map.copyOf(ceilings);
        }
    }
}
