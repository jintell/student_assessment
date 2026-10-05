package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservabilityAuditSeparationTests {

    private static final String FAILURE_PREFIX = "OBS_AUDIT_SINK_SEPARATION_VIOLATED: ";

    @Test
    void operationalLoggingAndAuditRemainIndependentSinks() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));
        List<String> violations = new ArrayList<>();

        for (JavaClass origin : classes) {
            origin.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass().getName())
                    .filter(target -> crossesSinkBoundary(origin.getPackageName(), target))
                    .forEach(
                            target ->
                                    violations.add(
                                            FAILURE_PREFIX
                                                    + origin.getName()
                                                    + " depends on "
                                                    + target));
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean crossesSinkBoundary(String originPackage, String targetClass) {
        if (originPackage.contains(".infra.observability")) {
            return targetClass.startsWith("org.meldtech.platform.audit.");
        }
        if (originPackage.startsWith("org.meldtech.platform.audit")) {
            return targetClass.startsWith("org.meldtech.platform.platform.infra.observability.")
                    || targetClass.startsWith("org.slf4j.")
                    || targetClass.startsWith("io.opentelemetry.")
                    || targetClass.startsWith("io.micrometer.");
        }
        return false;
    }
}
