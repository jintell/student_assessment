package org.meldtech.platform.platform.infra.observability;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class MetricCardinalityGate {

    private static final Pattern METRIC_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Set<String> REQUIRED_BUSINESS_METRICS =
            Set.of(
                    "exam_started_total",
                    "exam_finished_total",
                    "pin_validation_total",
                    "result_published_total",
                    "correction_applied_total",
                    "provisional_feedback_released_total");

    private MetricCardinalityGate() {}

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: MetricCardinalityGate <cardinality-contract> <approved-contract>");
        }
        verify(Path.of(arguments[0]), Path.of(arguments[1]));
    }

    static void verify(Path cardinalityContract, Path approvedContract) {
        JsonNode contract = ObservabilityContractFiles.readObject(cardinalityContract);
        JsonNode approved = ObservabilityContractFiles.readObject(approvedContract);
        Set<String> forbidden = textSet(contract, "forbiddenLabels", cardinalityContract);
        JsonNode approvedCardinality =
                ObservabilityContractFiles.required(
                        approved, "cardinalityContract", approvedContract);
        Set<String> approvedForbidden =
                textSet(approvedCardinality, "forbiddenMetricLabels", approvedContract);
        if (!approvedForbidden.equals(forbidden)) {
            throw ObservabilityContractFiles.invalid(
                    cardinalityContract, "forbidden labels differ from the approved contract");
        }

        JsonNode metrics =
                ObservabilityContractFiles.required(contract, "metrics", cardinalityContract);
        if (!metrics.isArray() || metrics.isEmpty()) {
            throw ObservabilityContractFiles.invalid(
                    cardinalityContract, "metrics must be a non-empty array");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode metric : metrics) {
            String name =
                    ObservabilityContractFiles.requiredText(metric, "name", cardinalityContract);
            if (!METRIC_NAME.matcher(name).matches() || !names.add(name)) {
                throw ObservabilityContractFiles.invalid(
                        cardinalityContract, "invalid or duplicate metric " + name);
            }
            int maxSeries =
                    ObservabilityContractFiles.requiredNonNegativeInt(
                            metric, "maxSeries", cardinalityContract);
            if (maxSeries == 0) {
                throw ObservabilityContractFiles.invalid(
                        cardinalityContract, "metric has no positive series budget " + name);
            }
            Set<String> labels = textSet(metric, "labels", cardinalityContract);
            Set<String> rejected = new LinkedHashSet<>(labels);
            rejected.retainAll(forbidden);
            if (!rejected.isEmpty()) {
                throw ObservabilityContractFiles.invalid(
                        cardinalityContract, "metric uses a forbidden label " + name);
            }
        }
        if (!names.containsAll(REQUIRED_BUSINESS_METRICS)) {
            throw ObservabilityContractFiles.invalid(
                    cardinalityContract, "business-event metric budgets are incomplete");
        }
    }

    private static Set<String> textSet(JsonNode parent, String field, Path source) {
        JsonNode values = ObservabilityContractFiles.required(parent, field, source);
        if (!values.isArray()) {
            throw ObservabilityContractFiles.invalid(source, field + " must be an array");
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isString()) {
                throw ObservabilityContractFiles.invalid(
                        source, field + " contains a non-text value");
            }
            String text = Objects.requireNonNull(value.stringValue(), "contract array value");
            if (text.isBlank() || !result.add(text)) {
                throw ObservabilityContractFiles.invalid(
                        source, field + " contains an invalid or duplicate value");
            }
        }
        return Set.copyOf(result);
    }
}
