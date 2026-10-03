package org.meldtech.platform.platform.infra.observability;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class QueryBudgetGate {

    private QueryBudgetGate() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: QueryBudgetGate <query-budget-contract>");
        }
        verify(Path.of(arguments[0]));
    }

    static void verify(Path budgetContract) {
        JsonNode contract = ObservabilityContractFiles.readObject(budgetContract);
        JsonNode routes = ObservabilityContractFiles.required(contract, "routes", budgetContract);
        if (!routes.isArray() || routes.isEmpty()) {
            throw ObservabilityContractFiles.invalid(
                    budgetContract, "routes must be a non-empty array");
        }

        Set<String> routeIds = new LinkedHashSet<>();
        for (JsonNode route : routes) {
            String routeId =
                    ObservabilityContractFiles.requiredText(route, "routeId", budgetContract);
            String routeType =
                    ObservabilityContractFiles.requiredText(route, "routeType", budgetContract);
            ObservabilityContractFiles.requiredText(route, "routeClass", budgetContract);
            int fixedOverhead =
                    ObservabilityContractFiles.requiredNonNegativeInt(
                            route, "fixedOverhead", budgetContract);
            int sliceBudget =
                    ObservabilityContractFiles.requiredNonNegativeInt(
                            route, "sliceBudget", budgetContract);
            int maxQueries =
                    ObservabilityContractFiles.requiredNonNegativeInt(
                            route, "maxQueries", budgetContract);
            if (!routeIds.add(routeId) || maxQueries != fixedOverhead + sliceBudget) {
                throw ObservabilityContractFiles.invalid(
                        budgetContract, "duplicate or incoherent budget for " + routeId);
            }
            if (!routeId.equals(routeIdFrom(routeType, budgetContract))) {
                throw ObservabilityContractFiles.invalid(
                        budgetContract, "route identifier drift for " + routeType);
            }
        }
        if (!routeIds.contains("platform.getConformanceReference")) {
            throw ObservabilityContractFiles.invalid(
                    budgetContract, "conformance-reference route has no query budget");
        }
    }

    private static String routeIdFrom(String routeType, Path source) {
        try {
            Class<?> type = Class.forName(routeType);
            Field routeId = type.getDeclaredField("ROUTE_ID");
            if (!Modifier.isStatic(routeId.getModifiers()) || !routeId.trySetAccessible()) {
                throw ObservabilityContractFiles.invalid(source, "ROUTE_ID is not accessible");
            }
            Object value = routeId.get(null);
            if (!(value instanceof String identifier) || identifier.isBlank()) {
                throw ObservabilityContractFiles.invalid(source, "ROUTE_ID is not non-blank text");
            }
            return identifier;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot inspect query-budget route " + routeType, exception);
        }
    }
}
