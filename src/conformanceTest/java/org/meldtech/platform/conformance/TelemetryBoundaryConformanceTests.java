package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryBoundaryConformanceTests {

    private static final String FAILURE_PREFIX = "OBS_TELEMETRY_BOUNDARY_VIOLATED: ";
    private static final String BUSINESS_EVENT_RECORDER =
            "org.meldtech.platform.shared.kernel.observability.BusinessEventRecorder";
    private static final String MICROMETER_ADAPTER =
            "org.meldtech.platform.platform.infra.observability.MicrometerBusinessEventRecorder";

    @Test
    void productionDomainAndSlicesUseOnlyKernelTelemetryPorts() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertTelemetryBoundary(classes);
    }

    @Test
    void rejectsDirectVendorMetricsFromASlice() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.telemetry");

        AssertionError failure =
                assertThrows(AssertionError.class, () -> assertTelemetryBoundary(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains(FAILURE_PREFIX));
        assertTrue(String.valueOf(failure.getMessage()).contains("BusinessEventRecorder"));
    }

    private static void assertTelemetryBoundary(Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            if (isDomainOrSlice(origin)) {
                origin.getDirectDependenciesFromSelf().stream()
                        .map(dependency -> dependency.getTargetClass().getName())
                        .filter(TelemetryBoundaryConformanceTests::isForbiddenTelemetryType)
                        .forEach(
                                target ->
                                        violations.add(
                                                FAILURE_PREFIX
                                                        + origin.getName()
                                                        + " depends on "
                                                        + target
                                                        + "; record business metrics through "
                                                        + "BusinessEventRecorder"));
            }
            if (implementsBusinessEventRecorder(origin)
                    && !origin.getName().equals(MICROMETER_ADAPTER)) {
                violations.add(
                        FAILURE_PREFIX
                                + origin.getName()
                                + " implements BusinessEventRecorder outside the approved adapter");
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean isDomainOrSlice(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return packageName.contains(".domain") || packageName.contains(".slice.");
    }

    private static boolean isForbiddenTelemetryType(String className) {
        return className.startsWith("io.micrometer.")
                || className.startsWith("io.opentelemetry.")
                || className.startsWith("org.meldtech.platform.platform.infra.observability.");
    }

    private static boolean implementsBusinessEventRecorder(JavaClass javaClass) {
        return javaClass.getAllRawInterfaces().stream()
                .anyMatch(type -> type.getName().equals(BUSINESS_EVENT_RECORDER));
    }
}
