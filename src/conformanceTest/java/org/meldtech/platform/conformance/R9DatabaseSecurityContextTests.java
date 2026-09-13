package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class R9DatabaseSecurityContextTests {

    private static final String CONNECTION_FACTORY = "io.r2dbc.spi.ConnectionFactory";
    private static final String CONNECTION = "io.r2dbc.spi.Connection";
    private static final String INITIALIZER =
            "org.meldtech.platform.platform.infra.persistence.SecurityContextInitializer";

    @Test
    void transactionsCanOpenOnlyThroughTheSecurityContextInitializer() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertTransactionsUseInitializer(classes);
    }

    @Test
    void rejectsAHandlerWithUndecoratedConnectionAccess() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.r9");

        AssertionError failure =
                assertThrows(AssertionError.class, () -> assertTransactionsUseInitializer(fixture));

        assertTrue(
                String.valueOf(failure.getMessage()).contains("R9_UNDECORATED_CONNECTION_ACCESS"));
    }

    private static void assertTransactionsUseInitializer(Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (isHandlerOrQueries(javaClass)
                    && javaClass.getDirectDependenciesFromSelf().stream()
                            .anyMatch(
                                    dependency ->
                                            dependency
                                                    .getTargetClass()
                                                    .getName()
                                                    .equals(CONNECTION_FACTORY))) {
                violations.add(
                        "R9_UNDECORATED_CONNECTION_ACCESS: "
                                + javaClass.getName()
                                + " can obtain a connection outside SecurityContextInitializer");
            }
            javaClass.getMethodCallsFromSelf().stream()
                    .filter(call -> call.getTargetOwner().getName().equals(CONNECTION))
                    .filter(call -> call.getName().equals("beginTransaction"))
                    .filter(call -> !isInitializerImplementation(javaClass))
                    .forEach(
                            call ->
                                    violations.add(
                                            "R9_UNDECORATED_CONNECTION_ACCESS: "
                                                    + javaClass.getName()
                                                    + " opens a transaction outside SecurityContextInitializer"));
        }
        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean isHandlerOrQueries(JavaClass javaClass) {
        return javaClass.getPackageName().contains(".slice.")
                && (javaClass.getSimpleName().equals("Handler")
                        || javaClass.getSimpleName().equals("Queries"));
    }

    private static boolean isInitializerImplementation(JavaClass javaClass) {
        return javaClass.getName().equals(INITIALIZER)
                || javaClass.getName().startsWith(INITIALIZER + "$");
    }
}
