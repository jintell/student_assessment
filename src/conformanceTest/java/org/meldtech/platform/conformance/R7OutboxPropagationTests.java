package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.outbox.api.OutboxWriter;
import org.meldtech.platform.shared.api.AtomicCrossModuleFlow;
import org.meldtech.platform.shared.api.CrossModuleCommandApi;
import org.meldtech.platform.shared.api.SynchronousAtomicFlow;

class R7OutboxPropagationTests {

    private static final List<String> BROKER_PACKAGES =
            List.of(
                    "com.rabbitmq.client",
                    "org.apache.kafka.clients.producer",
                    "org.springframework.amqp",
                    "org.springframework.kafka");

    @Test
    void adr023FlowEnumerationIsClosed() {
        Set<String> flows =
                Arrays.stream(AtomicCrossModuleFlow.values())
                        .map(AtomicCrossModuleFlow::flow)
                        .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("examaccess.verifyPinAndStartAttempt"), flows);
    }

    @Test
    void crossModulePropagationUsesOnlyReviewedPortsAndFlows() {
        List<String> violations = new ArrayList<>();
        JavaClasses classes = productionClasses();

        for (JavaClass origin : classes) {
            if (origin.getPackageName().startsWith("org.meldtech.platform.conformance")) {
                continue;
            }
            rejectDirectBrokerDependencies(origin, violations);
            rejectOutboxImplementationsOutsideAdapter(origin, violations);
            validateSynchronousWriteFlow(origin, violations);
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static void rejectDirectBrokerDependencies(JavaClass origin, List<String> violations) {
        if (origin.getPackageName().startsWith("org.meldtech.platform.outbox.infra")) {
            return;
        }
        origin.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getPackageName())
                .filter(
                        targetPackage ->
                                BROKER_PACKAGES.stream().anyMatch(targetPackage::startsWith))
                .forEach(
                        targetPackage ->
                                violations.add(
                                        violation(
                                                origin,
                                                "publishes through broker package "
                                                        + targetPackage
                                                        + "; use OutboxWriter")));
    }

    private static void rejectOutboxImplementationsOutsideAdapter(
            JavaClass origin, List<String> violations) {
        if (origin.isEquivalentTo(OutboxWriter.class)
                || !origin.isAssignableTo(OutboxWriter.class)) {
            return;
        }
        if (!origin.getPackageName().startsWith("org.meldtech.platform.outbox.infra")) {
            violations.add(
                    violation(
                            origin,
                            "implements OutboxWriter outside the outbox infrastructure adapter"));
        }
    }

    private static void validateSynchronousWriteFlow(JavaClass origin, List<String> violations) {
        boolean invokesCommandApi =
                origin.getDirectDependenciesFromSelf().stream()
                        .map(dependency -> dependency.getTargetClass())
                        .anyMatch(target -> target.isAssignableTo(CrossModuleCommandApi.class));
        if (!invokesCommandApi) {
            return;
        }
        if (!origin.isAnnotatedWith(SynchronousAtomicFlow.class)) {
            violations.add(
                    violation(
                            origin,
                            "uses a cross-module command without an ADR-023 flow declaration"));
            return;
        }
        AtomicCrossModuleFlow flow =
                origin.getAnnotationOfType(SynchronousAtomicFlow.class).value();
        if (flow != AtomicCrossModuleFlow.EXAM_ENTRY) {
            violations.add(violation(origin, "uses a flow not enumerated by ADR-023"));
        }
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.meldtech.platform");
    }

    private static String violation(JavaClass origin, String detail) {
        return "R7 propagation violated: "
                + origin.getName()
                + " "
                + detail
                + "; use OutboxWriter or an ADR-023 enumerated atomic flow.";
    }
}
