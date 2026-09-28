package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KernelPurityConformanceTests {

    private static final String KERNEL_PACKAGE = "org.meldtech.platform.shared.kernel";

    @Test
    void kernelTypesDependOnlyOnTheJdkReactiveStreamsAndTheKernel() {
        JavaClasses classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertKernelTypesDependOnlyOnAllowedTypes(classes);
    }

    @Test
    void rejectsASpringDependencyInTheKernel() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.shared.kernel.r6fixture");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertKernelTypesDependOnlyOnAllowedTypes(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("KERNEL-PURITY:"));
        assertTrue(String.valueOf(failure.getMessage()).contains("org.springframework"));
    }

    private static void assertKernelTypesDependOnlyOnAllowedTypes(JavaClasses classes) {
        List<String> violations = new ArrayList<>();

        classes.stream()
                .filter(KernelPurityConformanceTests::isKernelType)
                .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> !isAllowed(dependency.getTargetClass()))
                .forEach(dependency -> violations.add(violation(dependency)));

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static boolean isKernelType(JavaClass javaClass) {
        return javaClass.getPackageName().startsWith(KERNEL_PACKAGE)
                && !javaClass.getSimpleName().equals("package-info");
    }

    private static boolean isAllowed(JavaClass target) {
        String packageName = target.getPackageName();
        return target.isPrimitive()
                || packageName.startsWith("java.")
                || packageName.startsWith("javax.")
                || packageName.startsWith("org.reactivestreams")
                || packageName.startsWith(KERNEL_PACKAGE);
    }

    private static String violation(Dependency dependency) {
        return "KERNEL-PURITY: "
                + dependency.getOriginClass().getName()
                + " depends on forbidden "
                + dependency.getTargetClass().getName()
                + "; shared.kernel may use only JDK, Reactive Streams SPI, and kernel types.";
    }
}
