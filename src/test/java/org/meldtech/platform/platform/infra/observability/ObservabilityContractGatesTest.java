package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ObservabilityContractGatesTest {

    private static final Path APPROVED_CONTRACT =
            Path.of("ci/dor/FEAT-OBS-001/P0.8-observability-contract-dor.json");
    private static final Path CARDINALITY_CONTRACT =
            Path.of("config/observability/metric-cardinality.json");
    private static final Path QUERY_BUDGET_CONTRACT =
            Path.of("config/observability/query-budgets.json");

    @Test
    void checkedInContractsPassAllGates() {
        assertThatNoException()
                .isThrownBy(
                        () -> {
                            BusinessEventCompletenessGate.main(
                                    new String[] {APPROVED_CONTRACT.toString()});
                            MetricCardinalityGate.main(
                                    new String[] {
                                        CARDINALITY_CONTRACT.toString(),
                                        APPROVED_CONTRACT.toString()
                                    });
                            QueryBudgetGate.main(new String[] {QUERY_BUDGET_CONTRACT.toString()});
                        });
    }

    @Test
    void gateEntryPointsRejectMissingArguments() {
        assertThatThrownBy(() -> BusinessEventCompletenessGate.main(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved-contract");
        assertThatThrownBy(() -> MetricCardinalityGate.main(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardinality-contract");
        assertThatThrownBy(() -> QueryBudgetGate.main(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query-budget-contract");
    }

    @Test
    void businessEventGateRejectsUnapprovedContract(@TempDir Path directory) throws IOException {
        Path drifted =
                changedCopy(
                        APPROVED_CONTRACT,
                        directory.resolve("business-events.json"),
                        "\"status\": \"APPROVED\"",
                        "\"status\": \"DRAFT\"");

        assertThatThrownBy(() -> BusinessEventCompletenessGate.verify(drifted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contract is not approved");
    }

    @Test
    void businessEventGateRejectsRemovedOrUnregisteredMvpEvents(@TempDir Path directory)
            throws IOException {
        Path removed =
                changedCopy(
                        APPROVED_CONTRACT,
                        directory.resolve("removed-event.json"),
                        """
                            {
                              "eventCode": "EXAM_STARTED",
                              "metric": "exam_started_total",
                              "lifecycle": "MVP"
                            },
                        """,
                        "");
        Path added =
                changedCopy(
                        APPROVED_CONTRACT,
                        directory.resolve("unregistered-event.json"),
                        "\"businessEventMetrics\": [",
                        """
                        "businessEventMetrics": [
                            {
                              "eventCode": "SYNC_OUTCOME",
                              "metric": "sync_outcome_total",
                              "lifecycle": "MVP"
                            },
                        """);

        assertThatThrownBy(() -> BusinessEventCompletenessGate.verify(removed))
                .hasMessageContaining("enumeration and metric registry are incomplete");
        assertThatThrownBy(() -> BusinessEventCompletenessGate.verify(added))
                .hasMessageContaining("enumeration and metric registry are incomplete");
    }

    @Test
    void businessEventGateRejectsMalformedEventSections(@TempDir Path directory)
            throws IOException {
        Path nonArrayEvents =
                changedCopy(
                        APPROVED_CONTRACT,
                        directory.resolve("non-array-events.json"),
                        "\"businessEventMetrics\": [",
                        "\"businessEventMetrics\": \"invalid\", \"ignoredEvents\": [");
        Path nonMvpEvent =
                changedCopy(
                        APPROVED_CONTRACT,
                        directory.resolve("non-mvp-event.json"),
                        "\"lifecycle\": \"MVP\"",
                        "\"lifecycle\": \"POST_MVP\"");
        Path nonArrayAbsent =
                changedCopy(
                        APPROVED_CONTRACT,
                        directory.resolve("non-array-absent.json"),
                        "\"declaredAbsentBusinessEvents\": [",
                        "\"declaredAbsentBusinessEvents\": \"invalid\", \"ignoredAbsent\": [");

        assertThatThrownBy(() -> BusinessEventCompletenessGate.verify(nonArrayEvents))
                .hasMessageContaining("businessEventMetrics must be an array");
        assertThatThrownBy(() -> BusinessEventCompletenessGate.verify(nonMvpEvent))
                .hasMessageContaining("duplicate or non-MVP business event");
        assertThatThrownBy(() -> BusinessEventCompletenessGate.verify(nonArrayAbsent))
                .hasMessageContaining("declaredAbsentBusinessEvents must be an array");
    }

    @Test
    void cardinalityGateRejectsForbiddenMetricLabel(@TempDir Path directory) throws IOException {
        Path drifted =
                changedCopy(
                        CARDINALITY_CONTRACT,
                        directory.resolve("cardinality.json"),
                        "\"labels\": []",
                        "\"labels\": [\"tenantId\"]");

        assertThatThrownBy(() -> MetricCardinalityGate.verify(drifted, APPROVED_CONTRACT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metric uses a forbidden label");
    }

    @Test
    void queryBudgetGateRejectsIncoherentBudget(@TempDir Path directory) throws IOException {
        Path drifted =
                changedCopy(
                        QUERY_BUDGET_CONTRACT,
                        directory.resolve("query-budgets.json"),
                        "\"maxQueries\": 3",
                        "\"maxQueries\": 4");

        assertThatThrownBy(() -> QueryBudgetGate.verify(drifted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate or incoherent budget");
    }

    @Test
    void queryBudgetGateRejectsAnAddedReferenceSliceQuery() {
        String routeId = "platform.getConformanceReference";

        assertThatNoException()
                .isThrownBy(
                        () -> QueryBudgetGate.verifyQueryCount(QUERY_BUDGET_CONTRACT, routeId, 3));
        assertThatThrownBy(
                        () -> QueryBudgetGate.verifyQueryCount(QUERY_BUDGET_CONTRACT, routeId, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("used 4 queries; approved maximum is 3");
    }

    @Test
    void contractReaderRejectsMalformedAndNonObjectDocuments(@TempDir Path directory)
            throws IOException {
        Path malformed = directory.resolve("malformed.json");
        Files.writeString(malformed, "{");
        Path array = directory.resolve("array.json");
        Files.writeString(array, "[]");

        assertThatThrownBy(() -> ObservabilityContractFiles.readObject(malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read observability contract");
        assertThatThrownBy(() -> ObservabilityContractFiles.readObject(array))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("root must be an object");
    }

    @Test
    void contractFieldReadersRejectMissingBlankAndNegativeValues() {
        JsonNode contract = new ObjectMapper().readTree("{\"blank\":\" \",\"negative\":-1}");

        assertThatThrownBy(
                        () ->
                                ObservabilityContractFiles.required(
                                        contract, "missing", APPROVED_CONTRACT))
                .hasMessageContaining("missing field missing");
        assertThatThrownBy(
                        () ->
                                ObservabilityContractFiles.requiredText(
                                        contract, "blank", APPROVED_CONTRACT))
                .hasMessageContaining("must be non-blank text");
        assertThatThrownBy(
                        () ->
                                ObservabilityContractFiles.requiredNonNegativeInt(
                                        contract, "negative", APPROVED_CONTRACT))
                .hasMessageContaining("must be a non-negative integer");
    }

    private static Path changedCopy(Path source, Path target, String expected, String replacement)
            throws IOException {
        String contract = Files.readString(source);
        if (!contract.contains(expected)) {
            throw new IllegalStateException("Expected contract fragment is absent");
        }
        return Files.writeString(target, contract.replace(expected, replacement));
    }
}
