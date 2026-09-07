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
import org.reactivestreams.Publisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class R4TransactionBoundaryTests {

    @Test
    void handlersOwnOneReactiveTransactionAndNeverCallAnotherHandler() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertHandlersOwnOneReactiveTransactionAndNeverCallAnotherHandler(classes);
    }

    @Test
    void rejectsAHandlerCallingAnotherHandler() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.r4.calling");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () ->
                                assertHandlersOwnOneReactiveTransactionAndNeverCallAnotherHandler(
                                        fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("must not invoke handler"));
    }

    @Test
    void rejectsASecondTransactionInOneHandler() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.r4.nested");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () ->
                                assertHandlersOwnOneReactiveTransactionAndNeverCallAnotherHandler(
                                        fixture));

        assertTrue(
                String.valueOf(failure.getMessage())
                        .contains("must declare exactly one transactional entry method"));
    }

    private static void assertHandlersOwnOneReactiveTransactionAndNeverCallAnotherHandler(
            Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();

        for (JavaClass handler : classes) {
            if (!isHandler(handler)) {
                continue;
            }
            List<JavaMethod> transactionMethods =
                    handler.getMethods().stream()
                            .filter(method -> method.isAnnotatedWith(Transactional.class))
                            .toList();
            if (transactionMethods.size() != 1) {
                violations.add(
                        violation(handler, "must declare exactly one transactional entry method"));
            } else {
                JavaMethod transactionMethod = transactionMethods.getFirst();
                Transactional transactional =
                        transactionMethod.getAnnotationOfType(Transactional.class);
                if (transactional.propagation() != Propagation.REQUIRED) {
                    violations.add(
                            violation(
                                    handler,
                                    "must use REQUIRED propagation, not "
                                            + transactional.propagation()));
                }
                if (!transactionMethod.getRawReturnType().isAssignableTo(Publisher.class)) {
                    violations.add(
                            violation(handler, "transactional entry method must be reactive"));
                }
            }

            handler.getMethodCallsFromSelf().stream()
                    .filter(call -> isHandler(call.getTargetOwner()))
                    .filter(call -> !call.getTargetOwner().equals(handler))
                    .forEach(
                            call ->
                                    violations.add(
                                            violation(
                                                    handler,
                                                    "must not invoke handler "
                                                            + call.getTargetOwner().getName())));
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean isHandler(JavaClass javaClass) {
        return javaClass.getSimpleName().equals("Handler")
                && javaClass.getPackageName().contains(".slice.");
    }

    private static String violation(JavaClass handler, String detail) {
        return "R4 transaction boundary violated: "
                + handler.getName()
                + " "
                + detail
                + " and must not invoke another handler.";
    }
}
