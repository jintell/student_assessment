package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.outbox.OutboxWriter;

class AnswerAcceptanceOutboxTests {

    @Test
    void answerAcceptancePathHasNoOutboxDependency() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertAnswerAcceptanceHasNoOutboxDependency(classes);
    }

    @Test
    void rejectsAnswerAcceptanceOutboxFixture() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages(
                                "org.meldtech.platform.conformance.fixtures.answeracceptance");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertAnswerAcceptanceHasNoOutboxDependency(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("ADR-009"));
        assertTrue(String.valueOf(failure.getMessage()).contains("ARC-PLAT-006"));
    }

    private static void assertAnswerAcceptanceHasNoOutboxDependency(Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            if (!isAnswerAcceptancePath(origin)) {
                continue;
            }
            origin.getDirectDependenciesFromSelf().stream()
                    .filter(
                            dependency ->
                                    dependency.getTargetClass().isAssignableTo(OutboxWriter.class))
                    .forEach(
                            dependency ->
                                    violations.add(
                                            "ADR-009 and ARC-PLAT-006 prohibit an outbox write "
                                                    + "on the answer-acceptance path: "
                                                    + origin.getName()));
        }
        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean isAnswerAcceptancePath(JavaClass javaClass) {
        String name =
                (javaClass.getPackageName() + "." + javaClass.getSimpleName())
                        .toLowerCase(Locale.ROOT);
        return name.contains("answeracceptance")
                || (name.contains("delivery.slice")
                        && name.contains("answer")
                        && (name.contains("accept") || name.contains("submit")));
    }
}
