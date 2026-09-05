package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.RequestTenantId;
import org.meldtech.platform.shared.api.TenantScopedQuery;

class R5TenantQuerySignatureTests {

    @Test
    void everyTenantScopedQueryMethodTakesATenantIdentifier() {
        List<String> violations = new ArrayList<>();
        Iterable<JavaClass> classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("org.meldtech.platform");

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
