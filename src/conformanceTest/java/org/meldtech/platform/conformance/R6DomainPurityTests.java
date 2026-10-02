package org.meldtech.platform.conformance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class R6DomainPurityTests {

    private static final Set<String> AMBIENT_TIME_TYPES =
            Set.of(
                    "java.lang.System",
                    "java.time.Clock",
                    "java.time.Instant",
                    "java.time.LocalDate",
                    "java.time.LocalDateTime",
                    "java.time.OffsetDateTime",
                    "java.time.ZonedDateTime");

    @Test
    void domainCodeHasNoFrameworkOrAdapterDependencies() {
        assertDomainCodeHasNoFrameworkOrAdapterDependencies(productionClasses());
    }

    @Test
    void rejectsASpringDependencyInDomainCode() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.people.domain.r6fixture");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertDomainCodeHasNoFrameworkOrAdapterDependencies(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("R6 domain purity violated:"));
    }

    private static void assertDomainCodeHasNoFrameworkOrAdapterDependencies(JavaClasses classes) {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "org.springframework..",
                                "io.r2dbc..",
                                "io.micrometer..",
                                "io.opentelemetry..",
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
        assertUsesNoAmbientClockOutsideTheClockAbstraction(productionClasses());
    }

    @Test
    void rejectsAnAmbientTimeCallOutsideTheClockAbstraction() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.r6.time");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertUsesNoAmbientClockOutsideTheClockAbstraction(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("R6 controlled time violated:"));
    }

    private static void assertUsesNoAmbientClockOutsideTheClockAbstraction(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            if (isSystemClockAdapter(origin)) {
                continue;
            }
            origin.getMethodCallsFromSelf().stream()
                    .filter(R6DomainPurityTests::isAmbientTimeCall)
                    .forEach(
                            call ->
                                    violations.add(
                                            "R6 controlled time violated: "
                                                    + origin.getName()
                                                    + " calls ambient time source "
                                                    + call.getTarget().getFullName()
                                                    + "; inject shared.kernel Clock."));
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    @Test
    void gradingDomainUsesNoFloatingPointTypes() {
        assertGradingDomainUsesNoFloatingPointTypes(productionClasses());
    }

    @Test
    void gradingDomainUsesOnlyTheCanonicalRoundingHelper() {
        assertGradingDomainUsesOnlyTheCanonicalRoundingHelper(productionClasses());
    }

    @Test
    void rejectsFloatingPointInTheGradingDomain() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.grading.domain.r6fixture");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertGradingDomainUsesNoFloatingPointTypes(fixture));

        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("R6 exact decimal violated:"));
        assertTrue(message.contains("shared.kernel Decimal conventions"));
    }

    @Test
    void rejectsASecondRoundingHelperInTheGradingDomain() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.grading.domain.r6fixture");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertGradingDomainUsesOnlyTheCanonicalRoundingHelper(fixture));

        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("R6 exact decimal violated:"));
        assertTrue(message.contains("roundHalfUpToWholeNumber"));
    }

    private static void assertGradingDomainUsesNoFloatingPointTypes(JavaClasses classes) {
        Set<String> violations = new LinkedHashSet<>();
        for (JavaClass javaClass : classes) {
            if (!isScoringCode(javaClass)) {
                continue;
            }
            FloatingPointBytecodeInspector.inspect(javaClass, violations);
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static void assertGradingDomainUsesOnlyTheCanonicalRoundingHelper(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        classes.stream()
                .filter(R6DomainPurityTests::isScoringCode)
                .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
                .filter(R6DomainPurityTests::isDirectRoundingCall)
                .forEach(
                        call ->
                                violations.add(
                                        "R6 exact decimal violated: "
                                                + call.getOriginOwner().getName()
                                                + " declares a second rounding path via "
                                                + call.getTarget().getFullName()
                                                + "; use shared.kernel Decimal.roundHalfUpToWholeNumber."));

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));
    }

    private static boolean isSystemClockAdapter(JavaClass javaClass) {
        return javaClass.getName().equals("org.meldtech.platform.platform.infra.time.SystemClock");
    }

    private static boolean isAmbientTimeCall(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        String method = call.getTarget().getName();
        if (!AMBIENT_TIME_TYPES.contains(owner)) {
            return false;
        }
        if (owner.equals("java.time.Clock")) {
            return method.startsWith("system")
                    || method.equals("tickMillis")
                    || method.equals("tickSeconds")
                    || method.equals("tickMinutes");
        }
        return call.getTarget().getRawParameterTypes().isEmpty()
                && (method.equals("now")
                        || method.equals("currentTimeMillis")
                        || method.equals("nanoTime"));
    }

    private static boolean isDirectRoundingCall(JavaMethodCall call) {
        return call.getTargetOwner().getName().equals("java.math.BigDecimal")
                && call.getTarget().getName().equals("setScale");
    }

    private static boolean isScoringCode(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return packageName.endsWith(".grading")
                || packageName.contains(".grading.")
                || packageName.endsWith(".scoring")
                || packageName.contains(".scoring.");
    }
}
