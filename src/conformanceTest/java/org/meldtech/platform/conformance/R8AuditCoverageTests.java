package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.api.AuditEmitter;
import org.springframework.transaction.annotation.Transactional;

class R8AuditCoverageTests {

    @Test
    void everyMutatingHandlerEmitsAnAuditEventInsideItsTransactionMethod() {
        List<String> violations = new ArrayList<>();
        Iterable<JavaClass> classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("org.meldtech.platform");

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
                && javaClass.getPackageName().contains(".slice.")
                && !javaClass.getPackageName().startsWith("org.meldtech.platform.conformance");
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
