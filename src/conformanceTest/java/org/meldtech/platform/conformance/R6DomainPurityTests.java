package org.meldtech.platform.conformance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class R6DomainPurityTests {

    private static final Set<String> AMBIENT_TIME_TYPES =
            Set.of(
                    "java.lang.System",
                    "java.time.Instant",
                    "java.time.LocalDate",
                    "java.time.LocalDateTime",
                    "java.time.OffsetDateTime",
                    "java.time.ZonedDateTime");
    private static final Set<String> FLOATING_POINT_TYPES =
            Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    @Test
    void domainCodeHasNoFrameworkOrAdapterDependencies() {
        JavaClasses classes = productionClasses();
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "org.springframework..",
                                "io.r2dbc..",
                                "com.fasterxml.jackson..",
                                "..infra..",
                                "..slice..")
                        .as(
                                "R6 domain purity violated: domain code must use only domain and JDK types")
                        .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void productionCodeUsesNoAmbientClockOutsideTheClockAbstraction() {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : productionClasses()) {
            if (isClockAbstraction(origin)) {
                continue;
            }
            origin.getMethodCallsFromSelf().stream()
                    .filter(call -> AMBIENT_TIME_TYPES.contains(call.getTargetOwner().getName()))
                    .filter(call -> isAmbientTimeMethod(call.getTarget().getName()))
                    .filter(call -> call.getTarget().getRawParameterTypes().isEmpty())
                    .forEach(
                            call ->
                                    violations.add(
                                            "R6 domain purity violated: "
                                                    + origin.getName()
                                                    + " uses ambient time source "
                                                    + call.getTarget().getFullName()
                                                    + "; use the shared Clock abstraction."));
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    @Test
    void gradingDomainUsesNoFloatingPointTypes() {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : productionClasses()) {
            if (!javaClass.getPackageName().startsWith("org.meldtech.platform.grading.domain")) {
                continue;
            }
            javaClass.getFields().stream()
                    .filter(field -> FLOATING_POINT_TYPES.contains(field.getRawType().getName()))
                    .forEach(
                            field ->
                                    violations.add(
                                            floatingPointViolation(
                                                    javaClass, "field " + field.getFullName())));
            javaClass
                    .getMethods()
                    .forEach(
                            method -> {
                                if (FLOATING_POINT_TYPES.contains(
                                        method.getRawReturnType().getName())) {
                                    violations.add(
                                            floatingPointViolation(
                                                    javaClass,
                                                    "return type of " + method.getFullName()));
                                }
                                method.getRawParameterTypes().stream()
                                        .filter(
                                                type ->
                                                        FLOATING_POINT_TYPES.contains(
                                                                type.getName()))
                                        .forEach(
                                                type ->
                                                        violations.add(
                                                                floatingPointViolation(
                                                                        javaClass,
                                                                        "parameter of "
                                                                                + method
                                                                                        .getFullName())));
                            });
            javaClass
                    .getConstructors()
                    .forEach(
                            constructor ->
                                    constructor.getRawParameterTypes().stream()
                                            .filter(
                                                    type ->
                                                            FLOATING_POINT_TYPES.contains(
                                                                    type.getName()))
                                            .forEach(
                                                    type ->
                                                            violations.add(
                                                                    floatingPointViolation(
                                                                            javaClass,
                                                                            "constructor parameter"))));
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.meldtech.platform");
    }

    private static boolean isClockAbstraction(JavaClass javaClass) {
        return javaClass.getName().equals("org.meldtech.platform.shared.api.Clock")
                || javaClass.getPackageName().startsWith("org.meldtech.platform.shared.api.time");
    }

    private static boolean isAmbientTimeMethod(String methodName) {
        return methodName.equals("now")
                || methodName.equals("currentTimeMillis")
                || methodName.equals("nanoTime");
    }

    private static String floatingPointViolation(JavaClass javaClass, String location) {
        return "R6 domain purity violated: "
                + javaClass.getName()
                + " uses forbidden floating-point type in "
                + location
                + "; use exact Decimal conventions.";
    }
}
