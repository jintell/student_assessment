package org.meldtech.platform.platform.infra.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

record RuntimeRoleManifest(
        int schemaVersion,
        String status,
        String role,
        String workload,
        List<String> enabledCategories,
        List<String> forbiddenCategories,
        BusinessRoutes businessRoutes,
        List<EntryPoint> entryPoints,
        Management management,
        List<String> scalingSignals,
        @Nullable List<String> intendedCapabilities,
        @Nullable String prewarming,
        @Nullable String serviceAccount,
        @Nullable Boolean ownsDatabaseSchema,
        @Nullable Integer replicas,
        @Nullable Boolean autoscaling) {

    private static final Set<String> CATEGORIES =
            Set.of("slice-endpoint", "pin-distribution-endpoint", "broker-consumer", "sweep");

    static RuntimeRoleManifest load(RuntimeRole role) {
        try (InputStream input =
                new ClassPathResource("contracts/runtime-roles/" + role.profile() + ".json")
                        .getInputStream()) {
            return read(input, role);
        } catch (IOException exception) {
            throw invalid("manifest unavailable");
        }
    }

    static RuntimeRoleManifest read(InputStream input, RuntimeRole role) {
        RuntimeRoleManifest manifest;
        try {
            manifest =
                    JsonMapper.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .build()
                            .readValue(input, RuntimeRoleManifest.class);
        } catch (RuntimeException exception) {
            throw invalid("malformed manifest");
        }
        manifest.validate(role);
        return manifest;
    }

    private void validate(RuntimeRole expected) {
        if (schemaVersion != 1
                || !expected.profile().equals(role)
                || !("cbt-" + role).equals(workload)
                || status == null
                || !Set.of("DESIGN_BASELINE", "VERIFIED_INVENTORY").contains(status)
                || businessRoutes == null
                || management == null
                || scalingSignals == null
                || entryPoints == null) {
            throw invalid("manifest header");
        }
        Set<String> enabled = categories(enabledCategories);
        Set<String> forbidden = categories(forbiddenCategories);
        if (!java.util.Collections.disjoint(enabled, forbidden)
                || enabled.size() + forbidden.size() != CATEGORIES.size()) {
            throw invalid("category partition");
        }
        Set<String> identities = new HashSet<>();
        Set<String> routes = new HashSet<>();
        Set<String> routeIds = new HashSet<>();
        for (EntryPoint entry : entryPoints) {
            entry.validate();
            if (!enabled.contains(entry.kind()) || !identities.add(entry.identity())) {
                throw invalid("duplicate or forbidden entry point");
            }
            if (entry.method() != null
                    && (!routes.add(entry.method() + " " + entry.path())
                            || !routeIds.add(entry.routeId()))) {
                throw invalid("duplicate route");
            }
        }
    }

    private static Set<String> categories(List<String> values) {
        if (values == null
                || !CATEGORIES.containsAll(values)
                || new HashSet<>(values).size() != values.size()) {
            throw invalid("unknown or repeated category");
        }
        return Set.copyOf(values);
    }

    static IllegalStateException invalid(String detail) {
        return new IllegalStateException("RUNTIME_MANIFEST_INVALID: " + detail);
    }

    record EntryPoint(
            String type,
            String kind,
            @Nullable String routeId,
            @Nullable String method,
            @Nullable String path,
            @Nullable String sweepName,
            @Nullable String queue,
            @Nullable String factoryType,
            @Nullable String factoryMethod) {
        String identity() {
            return type + (factoryType == null ? "" : "@" + factoryType + "#" + factoryMethod);
        }

        void validate() {
            if (type == null
                    || !type.startsWith("org.meldtech.platform.")
                    || kind == null
                    || !CATEGORIES.contains(kind)
                    || (factoryType == null) != (factoryMethod == null)) {
                throw invalid("entry identity");
            }
            boolean endpoint = kind.endsWith("endpoint");
            if (endpoint
                    && (routeId == null
                            || routeId.isBlank()
                            || method == null
                            || path == null
                            || !path.startsWith("/"))) {
                throw invalid("route metadata");
            }
            if ("sweep".equals(kind) && (sweepName == null || sweepName.isBlank())) {
                throw invalid("sweep metadata");
            }
            if ("broker-consumer".equals(kind) && (queue == null || queue.isBlank())) {
                throw invalid("consumer metadata");
            }
        }
    }

    record BusinessRoutes(
            String policy,
            @Nullable Integer maximumCount,
            @Nullable List<Route> allowed,
            @Nullable List<Route> forbidden,
            @Nullable String requiredWhenFeatureDelivered) {}

    record Route(String method, String path) {}

    record Management(boolean separatePort, boolean ingressRouted, List<String> probePaths) {}
}
