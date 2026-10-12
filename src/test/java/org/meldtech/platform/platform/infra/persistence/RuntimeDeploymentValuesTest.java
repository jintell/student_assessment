package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class RuntimeDeploymentValuesTest {

    @Test
    void imageRecipeHasNoRoleSpecificStageArgumentOrEnvironment() throws IOException {
        List<String> instructions =
                Files.readAllLines(Path.of("deploy/docker/Dockerfile.runtime")).stream()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .toList();
        assertThat(instructions).hasSize(5);
        assertThat(instructions.get(0)).startsWith("FROM eclipse-temurin:21-jre-noble@sha256:");
        assertThat(instructions.subList(1, instructions.size()))
                .containsExactly(
                        "WORKDIR /application", "COPY application.jar application.jar",
                        "USER 10001:10001",
                                "ENTRYPOINT [\"java\", \"-jar\", \"/application/application.jar\"]");
    }

    @Test
    void shutdownBudgetIncludesPreStopAndLeavesFiveSecondsOfMargin() throws IOException {
        Map<String, Object> values;
        try (var input = Files.newInputStream(Path.of("deploy/runtime/values.yaml"))) {
            values = new Yaml().load(input);
        }
        Map<String, Object> common = object(values, "common");
        Map<String, Object> budget = object(common, "shutdownBudgetSeconds");
        assertThat(budget)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "preStop",
                                30,
                                "waitingPhases",
                                4,
                                "perPhase",
                                5,
                                "otherCleanupTotal",
                                5,
                                "margin",
                                5));
        int total =
                number(budget, "preStop")
                        + number(budget, "waitingPhases") * number(budget, "perPhase")
                        + number(budget, "otherCleanupTotal")
                        + number(budget, "margin");
        assertThat(common.get("terminationGracePeriodSeconds")).isEqualTo(total).isEqualTo(60);
        assertThat(object(common, "env"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "SERVER_SHUTDOWN",
                                "graceful",
                                "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE",
                                "5s"));
        assertThat(object(object(object(common, "lifecycle"), "preStop"), "exec").get("command"))
                .isEqualTo(List.of("/bin/sh", "-ec", "touch /run/cbt/draining; sleep 30"));
        assertThat(object(common, "securityContext").get("fsGroup")).isEqualTo(10001);
        assertThat(common.get("volumeMounts"))
                .isEqualTo(List.of(Map.of("name", "drain-state", "mountPath", "/run/cbt")));
    }

    @Test
    void pindistAllowsOnlyIngressControllerPodsInTheDedicatedIngressNamespace() throws IOException {
        Map<String, Object> policy;
        try (var input =
                Files.newInputStream(
                        Path.of("deploy/kubernetes/pindist-network-policy.yaml.template"))) {
            policy = new Yaml().load(input);
        }
        assertThat(policy.get("kind")).isEqualTo("NetworkPolicy");
        assertThat(object(policy, "metadata").get("namespace"))
                .isEqualTo("${CBT_APPLICATION_NAMESPACE}");
        Map<String, Object> spec = object(policy, "spec");
        assertThat(object(object(spec, "podSelector"), "matchLabels"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "app.kubernetes.io/name", "cbt-platform",
                                "app.kubernetes.io/component", "pindist"));
        assertThat(spec.get("policyTypes")).isEqualTo(List.of("Ingress"));
        // A single peer with both selectors means AND, never namespace-wide OR pod-wide access.
        Map<String, Object> namespaceSelector =
                Map.of(
                        "matchLabels",
                        Map.of("kubernetes.io/metadata.name", "${CBT_INGRESS_NAMESPACE}"));

        Map<String, Object> podSelector =
                Map.of(
                        "matchLabels",
                        Map.of(
                                "app.kubernetes.io/name", "${CBT_INGRESS_CONTROLLER_NAME}",
                                "app.kubernetes.io/component", "controller"));

        Map<String, Object> ingressPeer =
                Map.of("namespaceSelector", namespaceSelector, "podSelector", podSelector);

        Map<String, Object> ingressRule =
                Map.of(
                        "from", List.of(ingressPeer),
                        "ports", List.of(Map.of("protocol", "TCP", "port", 8080)));

        assertThat(spec.get("ingress")).isEqualTo(List.of(ingressRule));
        //        assertThat(spec.get("ingress"))
        //                .isEqualTo(
        //                        List.of(
        //                                Map.of(
        //                                        "from",
        //                                                List.of(
        //                                                        Map.of(
        //                                                                "namespaceSelector",
        //                                                                        Map.of(
        //
        // "matchLabels",
        //                                                                                Map.of(
        //
        // "kubernetes.io/metadata.name",
        //
        // "${CBT_INGRESS_NAMESPACE}")),
        //                                                                "podSelector",
        //                                                                        Map.of(
        //
        // "matchLabels",
        //                                                                                Map.of(
        //
        // "app.kubernetes.io/name",
        //
        //      "${CBT_INGRESS_CONTROLLER_NAME}",
        //
        // "app.kubernetes.io/component",
        //
        //      "controller")))),
        //                                        "ports",
        //                                                List.of(Map.of("protocol", "TCP", "port",
        // 8080)))));
    }

    @Test
    void pindistUsesADedicatedServiceAccountWithoutAnImplicitApiToken() throws IOException {
        Map<String, Object> values;
        try (var input = Files.newInputStream(Path.of("deploy/runtime/values.yaml"))) {
            values = new Yaml().load(input);
        }
        Map<String, Object> workloads = object(values, "workloads");
        assertThat(
                        workloads.values().stream()
                                .map(workload -> object(workload).get("serviceAccountName"))
                                .distinct())
                .hasSize(3);
        Map<String, Object> serviceAccount;
        try (var input =
                Files.newInputStream(
                        Path.of("deploy/kubernetes/pindist-service-account.yaml.template"))) {
            serviceAccount = new Yaml().load(input);
        }
        assertThat(serviceAccount.get("kind")).isEqualTo("ServiceAccount");
        assertThat(object(serviceAccount, "metadata").get("name"))
                .isEqualTo(object(workloads, "pindist").get("serviceAccountName"));
        assertThat(serviceAccount.get("automountServiceAccountToken")).isEqualTo(false);
        assertThat(object(object(workloads, "pindist"), "workloadIdentity").get("subject"))
                .isEqualTo("system:serviceaccount:${CBT_APPLICATION_NAMESPACE}:cbt-pindist");
    }

    @Test
    void roleBudgetsMatchApplicationPoolsAndTheApprovedScalingEnvelope() throws IOException {
        Map<String, Object> values;
        try (var input = Files.newInputStream(Path.of("deploy/runtime/values.yaml"))) {
            values = new Yaml().load(input);
        }
        Map<String, Object> workloads = object(values, "workloads");
        assertThat(workloads.keySet()).containsExactlyInAnyOrder("api", "worker", "pindist");
        Map<String, Integer> totals = Map.of("api", 14, "worker", 10, "pindist", 5);
        assertThat(object(values, "image").get("reference"))
                .isEqualTo("${CBT_PLATFORM_IMAGE_DIGEST}");
        Map<String, Integer> baselines = Map.of("api", 12, "worker", 6, "pindist", 2);
        Map<String, Integer> minimums = Map.of("api", 6, "worker", 3);
        Map<String, Integer> maximums = Map.of("api", 30, "worker", 24);
        for (String role : workloads.keySet()) {
            Map<String, Object> workload = object(workloads, role);
            assertThat(workload).doesNotContainKey("image");
            assertThat(object(workload, "env").get("SPRING_PROFILES_ACTIVE")).isEqualTo(role);
            assertThat(object(workload, "service").get("sessionAffinity")).isEqualTo("None");
            assertThat(workload.get("replicas")).isEqualTo(baselines.get(role));
            Map<String, Object> database = object(workload, "database");
            Map<String, Object> pools = object(database, "pools");
            assertThat(pools.values().stream().mapToInt(value -> (Integer) value).sum())
                    .isEqualTo(totals.get(role));
            assertThat(database.get("total")).isEqualTo(totals.get(role));
            assertThat(applicationPools(role)).containsAllEntriesOf(pools);
            Map<String, Object> hpa = object(workload, "hpa");
            assertThat(hpa.get("enabled")).isEqualTo(!role.equals("pindist"));
            if (!role.equals("pindist")) {
                assertThat(hpa.get("minReplicas")).isEqualTo(minimums.get(role));
                assertThat(hpa.get("maxReplicas")).isEqualTo(maximums.get(role));
            } else {
                assertThat(hpa).doesNotContainKeys("minReplicas", "maxReplicas", "signals");
            }
        }
        assertThat(object(workloads, "worker").get("prewarmedReplicas")).isEqualTo(10);
        assertThat(signalNames(object(workloads, "api")))
                .containsExactly("cpu", "exam_path_p95_seconds");
        assertThat(signalNames(object(workloads, "worker"))).containsExactly("grading_queue_depth");
    }

    private Map<String, Object> applicationPools(String role) throws IOException {
        try (var input = Files.newInputStream(Path.of("src/main/resources/application.yaml"))) {
            for (Object document : new Yaml().loadAll(input)) {
                Map<String, Object> config = object(document);
                Map<String, Object> spring = object(config, "spring");
                if (spring.containsKey("config")) {
                    Map<String, Object> springConfig = object(spring, "config");
                    if (springConfig.containsKey("activate")
                            && role.equals(object(springConfig, "activate").get("on-profile"))) {
                        Map<String, Object> result = new java.util.HashMap<>();
                        object(object(object(config, "cbt"), "database"), "pools")
                                .forEach(
                                        (name, pool) ->
                                                result.put(name, object(pool).get("max-size")));
                        return result;
                    }
                }
            }
        }
        throw new IllegalStateException("Missing application pool configuration for " + role);
    }

    private List<String> signalNames(Map<String, Object> workload) {
        return ((List<?>) Objects.requireNonNull(object(workload, "hpa").get("signals")))
                .stream().map(signal -> (String) object(signal).get("name")).toList();
    }

    private static Map<String, Object> object(Map<String, Object> parent, String key) {
        return object(Objects.requireNonNull(parent.get(key), key));
    }

    private static int number(Map<String, Object> parent, String key) {
        return (Integer) Objects.requireNonNull(parent.get(key), key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
