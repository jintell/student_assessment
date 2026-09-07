package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.api.AuditEmitter;
import org.springframework.transaction.annotation.Transactional;

class R8AuditCoverageTests {

    @Test
    void everyMutatingHandlerEmitsAnAuditEventInsideItsTransactionMethod() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertEveryMutatingHandlerEmitsAnAuditEventInsideItsTransactionMethod(classes);
    }

    @Test
    void rejectsAMutatingHandlerWithoutAnAuditEvent() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.r8");
        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () ->
                                assertEveryMutatingHandlerEmitsAnAuditEventInsideItsTransactionMethod(
                                        fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("R8 audit coverage violated:"));
    }

    private static void assertEveryMutatingHandlerEmitsAnAuditEventInsideItsTransactionMethod(
            Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();

        for (JavaClass handler : classes) {
            if (!isProductionHandler(handler)) {
                continue;
            }
            handler.getMethods().stream()
                    .filter(method -> method.isAnnotatedWith(Transactional.class))
                    .filter(method -> !method.getAnnotationOfType(Transactional.class).readOnly())
                    .filter(method -> !emitsAuditEvent(method))
                    .forEach(
                            method ->
                                    violations.add(
                                            "R8 audit coverage violated: mutating handler "
                                                    + handler.getName()
                                                    + "."
                                                    + method.getName()
                                                    + " can complete without emitting an audit event "
                                                    + "in its transaction."));
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean isProductionHandler(JavaClass javaClass) {
        return javaClass.getSimpleName().equals("Handler")
                && javaClass.getPackageName().contains(".slice.");
    }

    private static boolean emitsAuditEvent(JavaMethod method) {
        return method.getMethodCallsFromSelf().stream()
                .anyMatch(
                        call ->
                                call.getTarget().getName().equals("emit")
                                        && call.getTargetOwner()
                                                .isAssignableTo(AuditEmitter.class));
    }
}
