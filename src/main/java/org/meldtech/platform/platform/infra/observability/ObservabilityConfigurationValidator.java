package org.meldtech.platform.platform.infra.observability;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

final class ObservabilityConfigurationValidator {

    private static final String FAILURE_PREFIX = "OBSERVABILITY_CONFIGURATION_INVALID: ";
    private static final Duration MINIMUM_EXPORT_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_EXPORT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MINIMUM_ITEM_AGE = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_ITEM_AGE = Duration.ofMinutes(5);
    private static final Duration MINIMUM_CONTINUATION_AGE = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_CONTINUATION_AGE = Duration.ofHours(24);
    private static final Set<String> REQUIRED_CEILINGS =
            Set.of(
                    "active-sessions",
                    "replicas",
                    "queues",
                    "roles",
                    "policies",
                    "shards",
                    "routes",
                    "slices");

    private final ObservabilityProperties properties;
    private final boolean local;

    ObservabilityConfigurationValidator(
            ObservabilityProperties properties, Environment environment) {
        this(
                properties,
                Objects.requireNonNull(environment, "environment")
                        .acceptsProfiles(Profiles.of("local")));
    }

    ObservabilityConfigurationValidator(ObservabilityProperties properties, boolean local) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.local = local;
    }

    void validate() {
        validateResource(properties.resource());
        validateCollector(properties.collector());
        validateSampling(properties.sampling());
        validateExport(properties.export());
        validateTrace(properties.trace());
        validateRedaction(properties.redaction());
        validateCandidateHash(properties.candidateHash());
        validateMetricCeilings(properties.metricCatalogues());
    }

    private void validateResource(ObservabilityProperties.Resource resource) {
        require(resource, "resource");
        requireText(resource.serviceName(), "resource.service-name");
        requireText(resource.environment(), "resource.environment");
        String role = requireText(resource.role(), "resource.role");
        if (!Set.of("api", "worker", "pindist").contains(role)) {
            throw invalid("resource.role", "must be a registered runtime role");
        }
    }

    private void validateCollector(ObservabilityProperties.Collector collector) {
        require(collector, "collector");
        requireEndpoint(collector.endpoint(), "collector.endpoint");
        if (collector.protocol() != ObservabilityProperties.Protocol.GRPC) {
            throw invalid("collector.protocol", "must be grpc");
        }
        require(collector.endpoints(), "collector.endpoints");
        requireEndpoint(collector.endpoints().traces(), "collector.endpoints.traces");
        requireEndpoint(collector.endpoints().metrics(), "collector.endpoints.metrics");
        requireEndpoint(collector.endpoints().logs(), "collector.endpoints.logs");
        require(collector.tls(), "collector.tls");
        if (!local) {
            if (!Boolean.TRUE.equals(collector.tls().enabled())) {
                throw invalid("collector.tls.enabled", "must be true outside local");
            }
            requireText(
                    collector.tls().trustCertificateReference(),
                    "collector.tls.trust-certificate-reference");
            requireText(
                    collector.tls().clientCertificateReference(),
                    "collector.tls.client-certificate-reference");
            requireText(
                    collector.tls().clientPrivateKeyReference(),
                    "collector.tls.client-private-key-reference");
        }
    }

    private void validateSampling(ObservabilityProperties.Sampling sampling) {
        require(sampling, "sampling");
        requireRatio(sampling.examEntryHeadRatio(), 1.0d, "sampling.exam-entry-head-ratio");
        requireRatio(sampling.gradingHeadRatio(), 1.0d, "sampling.grading-head-ratio");
        requireRatio(sampling.standardExportRatio(), 1.0d, "sampling.standard-export-ratio");
        requireRatio(sampling.standardTailRatio(), 0.10d, "sampling.standard-tail-ratio");
    }

    private void validateExport(ObservabilityProperties.Export export) {
        require(export, "export");
        requireDuration(
                export.timeout(), MINIMUM_EXPORT_TIMEOUT, MAXIMUM_EXPORT_TIMEOUT, "export.timeout");
        require(export.queues(), "export.queues");
        validateQueue("export.queues.traces", export.queues().traces(), export.timeout());
        validateQueue("export.queues.metrics", export.queues().metrics(), export.timeout());
        validateQueue("export.queues.logs", export.queues().logs(), export.timeout());
    }

    private void validateQueue(
            String name, ObservabilityProperties.Queue queue, Duration exportTimeout) {
        require(queue, name);
        Integer capacity = queue.capacity();
        if (capacity == null
                || capacity < 256
                || capacity > 65_536
                || (capacity & (capacity - 1)) != 0) {
            throw invalid(name + ".capacity", "must be a power of two in [256, 65536]");
        }
        int maximumBatch = Math.min(8_192, capacity / 2);
        if (queue.batchSize() == null
                || queue.batchSize() < 1
                || queue.batchSize() > maximumBatch) {
            throw invalid(name + ".batch-size", "is outside its bounded range");
        }
        requireDuration(
                queue.itemMaxAge(), MINIMUM_ITEM_AGE, MAXIMUM_ITEM_AGE, name + ".item-max-age");
        if (queue.itemMaxAge().compareTo(exportTimeout) <= 0) {
            throw invalid(name + ".item-max-age", "must exceed export.timeout");
        }
    }

    private void validateTrace(ObservabilityProperties.Trace trace) {
        require(trace, "trace");
        requireDuration(
                trace.maxContinuationAge(),
                MINIMUM_CONTINUATION_AGE,
                MAXIMUM_CONTINUATION_AGE,
                "trace.max-continuation-age");
    }

    private void validateRedaction(ObservabilityProperties.Redaction redaction) {
        require(redaction, "redaction");
        if (!SecretFieldPattern.permittedKeyFields().equals(redaction.permittedKeyFields())) {
            throw invalid("redaction.permitted-key-fields", "must match the approved catalogue");
        }
    }

    private void validateCandidateHash(ObservabilityProperties.CandidateHash candidateHash) {
        require(candidateHash, "candidate-hash");
        String secretReference =
                requireText(candidateHash.secretReference(), "candidate-hash.secret-reference");
        if (local) {
            return;
        }
        try {
            Path secretPath = Path.of(secretReference).normalize();
            if (!secretPath.isAbsolute() || !secretPath.startsWith(Path.of("/run/secrets"))) {
                throw invalid(
                        "candidate-hash.secret-reference",
                        "must resolve from the runtime secret mount outside local");
            }
        } catch (InvalidPathException exception) {
            throw invalid(
                    "candidate-hash.secret-reference",
                    "must resolve from the runtime secret mount outside local");
        }
    }

    private void validateMetricCeilings(ObservabilityProperties.MetricCatalogues metricCatalogues) {
        require(metricCatalogues, "metric-catalogues");
        Map<String, Integer> ceilings = metricCatalogues.ceilings();
        if (ceilings == null || !ceilings.keySet().containsAll(REQUIRED_CEILINGS)) {
            throw invalid("metric-catalogues.ceilings", "is incomplete");
        }
        for (String name : REQUIRED_CEILINGS) {
            Integer ceiling = ceilings.get(name);
            if (ceiling == null || ceiling < 1) {
                throw invalid("metric-catalogues.ceilings." + name, "must be positive");
            }
        }
    }

    private void requireEndpoint(URI endpoint, String name) {
        if (endpoint == null
                || !endpoint.isAbsolute()
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || endpoint.getHost() == null) {
            throw invalid(name, "must be an absolute collector URI without user-info or suffix");
        }
        if (!local && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw invalid(name, "must use https outside local");
        }
    }

    private static void requireRatio(Double value, double expected, String name) {
        if (value == null || Double.compare(value, expected) != 0) {
            throw invalid(name, "does not match the approved sampling contract");
        }
    }

    private static void requireDuration(
            Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw invalid(name, "is outside its bounded range");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name, "is required");
        }
        return value;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw invalid(name, "is required");
        }
        return value;
    }

    private static IllegalStateException invalid(String setting, String reason) {
        return new IllegalStateException(FAILURE_PREFIX + setting + " " + reason);
    }
}
