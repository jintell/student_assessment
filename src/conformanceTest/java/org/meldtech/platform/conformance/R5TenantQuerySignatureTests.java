package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.RequestTenantId;
import org.meldtech.platform.shared.api.TenantScopedQuery;

class R5TenantQuerySignatureTests {

    @Test
    void everyTenantScopedQueryMethodTakesATenantIdentifier() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertEveryTenantScopedQueryMethodTakesATenantIdentifier(classes);
    }

    @Test
    void rejectsATenantQueryWithoutATenantIdentifier() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.r5");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertEveryTenantScopedQueryMethodTakesATenantIdentifier(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("R5 tenant query violated:"));
    }

    private static void assertEveryTenantScopedQueryMethodTakesATenantIdentifier(
            Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();

        for (JavaClass queryType : classes) {
            if (queryType.isEquivalentTo(TenantScopedQuery.class)
                    || !queryType.isAssignableTo(TenantScopedQuery.class)) {
                continue;
            }
            queryType.getMethods().stream()
                    .filter(method -> method.getOwner().equals(queryType))
                    .filter(
                            method ->
                                    method.getRawParameterTypes().stream()
                                            .noneMatch(
                                                    parameter ->
                                                            parameter.isAssignableTo(
                                                                    RequestTenantId.class)))
                    .forEach(
                            method ->
                                    violations.add(
                                            "R5 tenant query violated: "
                                                    + queryType.getName()
                                                    + "."
                                                    + method.getName()
                                                    + " accesses tenant-scoped data without a "
                                                    + "RequestTenantId parameter."));
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }
}
