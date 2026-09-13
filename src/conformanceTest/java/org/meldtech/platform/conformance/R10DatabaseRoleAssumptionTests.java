package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.AtomicCrossModuleFlow;
import org.meldtech.platform.shared.api.SynchronousAtomicFlow;

class R10DatabaseRoleAssumptionTests {

    private static final String ASSUMABLE_ROLE =
            "org.meldtech.platform.platform.infra.persistence.AssumableDatabaseRole";
    private static final String INITIALIZER =
            "org.meldtech.platform.platform.infra.persistence.SecurityContextInitializer";

    @Test
    void handlersCannotSelectRolesOutsideTheClosedPolicy() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertClosedRolePolicy(classes);
    }

    @Test
    void rejectsAHandlerAcceptingADynamicRole() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.r10");

        AssertionError failure =
                assertThrows(AssertionError.class, () -> assertClosedRolePolicy(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("R10_DYNAMIC_ROLE_INPUT"));
    }

    private static void assertClosedRolePolicy(Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (!isHandler(javaClass)) {
                continue;
            }
            javaClass.getFields().stream()
                    .filter(field -> field.getRawType().isEquivalentTo(String.class))
                    .filter(
                            field ->
                                    field.getName()
                                            .toLowerCase(java.util.Locale.ROOT)
                                            .contains("role"))
                    .forEach(
                            field ->
                                    violations.add(
                                            "R10_DYNAMIC_ROLE_INPUT: "
                                                    + javaClass.getName()
                                                    + " accepts a role outside the compile-time enumeration"));
            javaClass.getDirectDependenciesFromSelf().stream()
                    .filter(
                            dependency ->
                                    dependency.getTargetClass().getName().equals(ASSUMABLE_ROLE)
                                            || dependency
                                                    .getTargetClass()
                                                    .getName()
                                                    .equals(INITIALIZER))
                    .forEach(
                            dependency ->
                                    violations.add(
                                            "R10_ROLE_NOT_ALLOWED: "
                                                    + javaClass.getName()
                                                    + " bypasses the handler-to-role policy"));
            if (javaClass.isAnnotatedWith(SynchronousAtomicFlow.class)) {
                AtomicCrossModuleFlow flow =
                        javaClass.getAnnotationOfType(SynchronousAtomicFlow.class).value();
                if (flow != AtomicCrossModuleFlow.EXAM_ENTRY
                        || !flow.compositeRole().equals("app_txn_examentry")) {
                    violations.add(
                            "R10_COMPOSITE_ROLE_UNDECLARED: "
                                    + javaClass.getName()
                                    + " references a flow absent from ADR-023");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean isHandler(JavaClass javaClass) {
        return (javaClass.getSimpleName().equals("Handler")
                        && javaClass.getPackageName().contains(".slice."))
                || javaClass.getPackageName().contains(".fixtures.r10");
    }
}
