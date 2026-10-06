package org.meldtech.platform.platform.infra.observability;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class MetricCardinalityGuard {

    private static final String FAILURE_PREFIX = "Metric cardinality registration rejected: ";

    private final Set<String> forbiddenLabels;
    private final Map<String, Registration> approvedRegistrations;

    private MetricCardinalityGuard(
            Set<String> forbiddenLabels, Map<String, Registration> approvedRegistrations) {
        this.forbiddenLabels = Set.copyOf(forbiddenLabels);
        this.approvedRegistrations = Map.copyOf(approvedRegistrations);
    }

    static MetricCardinalityGuard from(Path contractPath) {
        JsonNode contract = ObservabilityContractFiles.readObject(contractPath);
        Set<String> forbidden = textSet(contract, "forbiddenLabels", contractPath);
        JsonNode metrics = ObservabilityContractFiles.required(contract, "metrics", contractPath);
        if (!metrics.isArray() || metrics.isEmpty()) {
            throw rejected("contract contains no metric definitions");
        }
        Map<String, Registration> registrations = new LinkedHashMap<>();
        for (JsonNode metric : metrics) {
            String name = ObservabilityContractFiles.requiredText(metric, "name", contractPath);
            Set<String> labels = textSet(metric, "labels", contractPath);
            int maxSeries =
                    ObservabilityContractFiles.requiredNonNegativeInt(
                            metric, "maxSeries", contractPath);
            Registration registration = new Registration(name, labels, maxSeries);
            if (registrations.put(name, registration) != null) {
                throw rejected("contract contains duplicate metric " + name);
            }
            verifyBounded(registration, forbidden);
        }
        return new MetricCardinalityGuard(forbidden, registrations);
    }

    void verifyRegistration(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        verifyBounded(registration, forbiddenLabels);
        Registration approved = approvedRegistrations.get(registration.name());
        if (approved == null) {
            throw rejected(registration.name() + " is not in the approved metric contract");
        }
        if (!approved.labels().equals(registration.labels())
                || approved.maxSeries() != registration.maxSeries()) {
            throw rejected(
                    registration.name()
                            + " label declaration is not bounded by the approved contract");
        }
    }

    private static void verifyBounded(Registration registration, Set<String> forbiddenLabels) {
        if (registration.name().isBlank()) {
            throw rejected("metric name is blank");
        }
        if (registration.maxSeries() <= 0) {
            throw rejected(registration.name() + " has no positive series ceiling");
        }
        Set<String> rejectedLabels = new LinkedHashSet<>(registration.labels());
        rejectedLabels.retainAll(forbiddenLabels);
        if (!rejectedLabels.isEmpty()) {
            throw rejected(
                    registration.name() + " declares forbidden unbounded labels " + rejectedLabels);
        }
        TelemetrySchemaGate.verifyFieldNames("metric label", registration.labels());
    }

    private static Set<String> textSet(JsonNode parent, String field, Path source) {
        JsonNode values = ObservabilityContractFiles.required(parent, field, source);
        if (!values.isArray()) {
            throw rejected(field + " must be an array");
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isString()) {
                throw rejected(field + " contains a non-text value");
            }
            String text = Objects.requireNonNull(value.stringValue(), "metric contract value");
            if (text.isBlank() || !result.add(text)) {
                throw rejected(field + " contains a blank or duplicate value");
            }
        }
        return Set.copyOf(result);
    }

    private static IllegalStateException rejected(String detail) {
        return new IllegalStateException(FAILURE_PREFIX + detail);
    }

    record Registration(String name, Set<String> labels, int maxSeries) {

        Registration {
            Objects.requireNonNull(name, "name");
            labels = Set.copyOf(Objects.requireNonNull(labels, "labels"));
        }
    }
}
