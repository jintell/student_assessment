package org.meldtech.platform.platform.infra.isolation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.shared.api.IsolationOperation;
import org.meldtech.platform.shared.api.IsolationScenarioProvider;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import tools.jackson.databind.ObjectMapper;

final class TenantIsolationMatrixGenerator {

    private final ObjectMapper objectMapper;

    TenantIsolationMatrixGenerator() {
        this(new ObjectMapper());
    }

    TenantIsolationMatrixGenerator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    IsolationMatrix generate(
            Collection<? extends PolicyProtectedRoute> routes,
            Collection<? extends IsolationScenarioProvider> providers) {
        List<RouteDescriptor> descriptors =
                routes.stream()
                        .map(PolicyProtectedRoute::descriptor)
                        .sorted(Comparator.comparing(RouteDescriptor::routeId))
                        .toList();
        requireUnique(descriptors.stream().map(RouteDescriptor::routeId).toList(), "route");
        requireUnique(
                providers.stream().map(IsolationScenarioProvider::routeId).toList(), "provider");

        Set<String> tenantRouteIds =
                descriptors.stream()
                        .filter(descriptor -> descriptor.scope() == RouteDescriptor.Scope.TENANT)
                        .map(RouteDescriptor::routeId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> providerIds =
                providers.stream()
                        .map(IsolationScenarioProvider::routeId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        requireEqual(
                "tenant route ids and scenario-provider route ids", tenantRouteIds, providerIds);
        for (IsolationScenarioProvider provider : providers) {
            if (!provider.operations().equals(EnumSet.allOf(IsolationOperation.class))) {
                throw new IllegalStateException(
                        "Isolation provider "
                                + provider.routeId()
                                + " must cover READ, WRITE and ENUMERATE");
            }
        }

        List<AssertionRow> assertions =
                descriptors.stream()
                        .filter(descriptor -> descriptor.scope() == RouteDescriptor.Scope.TENANT)
                        .flatMap(
                                descriptor ->
                                        EnumSet.allOf(IsolationOperation.class).stream()
                                                .map(
                                                        operation ->
                                                                AssertionRow.from(
                                                                        descriptor, operation)))
                        .toList();
        List<PlatformExclusion> platformExclusions =
                descriptors.stream()
                        .filter(descriptor -> descriptor.scope() == RouteDescriptor.Scope.PLATFORM)
                        .map(
                                descriptor ->
                                        new PlatformExclusion(
                                                descriptor.routeId(),
                                                descriptor
                                                        .platformOperation()
                                                        .orElseThrow()
                                                        .name()))
                        .toList();
        IsolationMatrix matrix = new IsolationMatrix(1, assertions, platformExclusions);
        requireEqual("tenant route ids and matrix route ids", tenantRouteIds, matrix.routeIds());
        return matrix;
    }

    void write(IsolationMatrix matrix, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), matrix);
    }

    private static void requireUnique(List<String> ids, String label) {
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalStateException("Duplicate isolation " + label + " id");
        }
    }

    private static void requireEqual(String label, Set<String> expected, Set<String> actual) {
        if (!expected.equals(actual)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new HashSet<>(actual);
            extra.removeAll(expected);
            throw new IllegalStateException(
                    label + " differ: missing=" + missing + ", extra=" + extra);
        }
    }

    record IsolationMatrix(
            int formatVersion,
            List<AssertionRow> assertions,
            List<PlatformExclusion> platformExclusions) {

        Set<String> routeIds() {
            return assertions.stream()
                    .map(AssertionRow::routeId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    record AssertionRow(
            String routeId,
            String method,
            String pathTemplate,
            String owningModule,
            IsolationOperation operation,
            int expectedForeignResourceStatus) {

        private static AssertionRow from(RouteDescriptor descriptor, IsolationOperation operation) {
            return new AssertionRow(
                    descriptor.routeId(),
                    descriptor.method().name(),
                    descriptor.pathTemplate(),
                    descriptor.owningModule(),
                    operation,
                    404);
        }
    }

    record PlatformExclusion(String routeId, String platformOperation) {}
}
