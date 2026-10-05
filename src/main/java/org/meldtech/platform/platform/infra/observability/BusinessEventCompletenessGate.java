package org.meldtech.platform.platform.infra.observability;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.meldtech.platform.shared.kernel.observability.BusinessEventCode;
import tools.jackson.databind.JsonNode;

public final class BusinessEventCompletenessGate {

    private static final Set<String> DECLARED_ABSENT = Set.of("SYNC_OUTCOME", "PAYMENT_OUTCOME");

    private BusinessEventCompletenessGate() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: BusinessEventCompletenessGate <approved-contract>");
        }
        verify(Path.of(arguments[0]));
    }

    static void verify(Path approvedContract) {
        JsonNode root = ObservabilityContractFiles.readObject(approvedContract);
        if (!"APPROVED"
                .equals(
                        ObservabilityContractFiles.requiredText(
                                root, "status", approvedContract))) {
            throw ObservabilityContractFiles.invalid(approvedContract, "contract is not approved");
        }
        JsonNode events =
                ObservabilityContractFiles.required(root, "businessEventMetrics", approvedContract);
        if (!events.isArray()) {
            throw ObservabilityContractFiles.invalid(
                    approvedContract, "businessEventMetrics must be an array");
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (JsonNode event : events) {
            String code =
                    ObservabilityContractFiles.requiredText(event, "eventCode", approvedContract);
            String metric =
                    ObservabilityContractFiles.requiredText(event, "metric", approvedContract);
            String lifecycle =
                    ObservabilityContractFiles.requiredText(event, "lifecycle", approvedContract);
            if (!"MVP".equals(lifecycle) || actual.put(code, metric) != null) {
                throw ObservabilityContractFiles.invalid(
                        approvedContract, "duplicate or non-MVP business event " + code);
            }
        }
        if (!registeredEvents().equals(actual)) {
            throw ObservabilityContractFiles.invalid(
                    approvedContract,
                    "MVP business-event enumeration and metric registry are incomplete");
        }

        JsonNode absent =
                ObservabilityContractFiles.required(
                        root, "declaredAbsentBusinessEvents", approvedContract);
        if (!absent.isArray()) {
            throw ObservabilityContractFiles.invalid(
                    approvedContract, "declaredAbsentBusinessEvents must be an array");
        }
        Set<String> actualAbsent = new java.util.LinkedHashSet<>();
        for (JsonNode node : absent) {
            if (!node.isString()) {
                throw ObservabilityContractFiles.invalid(
                        approvedContract, "declared absent event must be text");
            }
            String code =
                    java.util.Objects.requireNonNull(node.stringValue(), "declared absent event");
            if (!actualAbsent.add(code)) {
                throw ObservabilityContractFiles.invalid(
                        approvedContract, "declared absent event is duplicated");
            }
        }
        if (!DECLARED_ABSENT.equals(actualAbsent)) {
            throw ObservabilityContractFiles.invalid(
                    approvedContract, "Post-MVP absent set has drifted");
        }
    }

    private static Map<String, String> registeredEvents() {
        Map<String, String> events = new LinkedHashMap<>();
        for (Map.Entry<BusinessEventCode, String> registration :
                MicrometerBusinessEventRecorder.registeredMetricNames().entrySet()) {
            events.put(registration.getKey().name(), registration.getValue());
        }
        return Map.copyOf(events);
    }
}
