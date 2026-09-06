package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.SlicePolicy;

class RoutePolicyCoverageTests {

    @Test
    void everyProductionRouteHasExactlyOneSlicePolicy() {
        JavaClasses productionClasses =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertPolicyCoverage(productionClasses);
    }

    @Test
    void conformanceGateRejectsADeliberatePolicyOmission() {
        JavaClasses fixture = new ClassFileImporter().importClasses(MissingPolicyRoute.class);

        AssertionError failure =
                assertThrows(AssertionError.class, () -> assertPolicyCoverage(fixture));

        assertTrue(
                String.valueOf(failure.getMessage())
                        .contains("has 0 policies; exactly one is required"));
    }

    private static void assertPolicyCoverage(JavaClasses classes) {
        List<JavaClass> routes =
                classes.stream().filter(RoutePolicyCoverageTests::isRoute).toList();
        List<JavaClass> policies =
                classes.stream().filter(RoutePolicyCoverageTests::isPolicy).toList();
        List<String> violations = new ArrayList<>();

        for (JavaClass route : routes) {
            long policyCount =
                    policies.stream()
                            .filter(
                                    policy ->
                                            policy.getPackageName().equals(route.getPackageName()))
                            .count();
            if (policyCount != 1) {
                violations.add(
                        "Route-policy coverage violated: "
                                + route.getName()
                                + " has "
                                + policyCount
                                + " policies; exactly one is required in "
                                + route.getPackageName()
                                + ".");
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean isRoute(JavaClass javaClass) {
        return !javaClass.isInterface() && javaClass.isAssignableTo(PolicyProtectedRoute.class);
    }

    private static boolean isPolicy(JavaClass javaClass) {
        return !javaClass.isInterface() && javaClass.isAssignableTo(SlicePolicy.class);
    }

    private abstract static class MissingPolicyRoute implements PolicyProtectedRoute {}
}
