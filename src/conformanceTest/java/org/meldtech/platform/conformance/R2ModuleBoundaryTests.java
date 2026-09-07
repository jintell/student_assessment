package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.CbtPlatformApplication;
import org.springframework.modulith.core.ApplicationModules;

class R2ModuleBoundaryTests {

    private static final String BASE_PACKAGE = "org.meldtech.platform";
    private static final ImportOption PRODUCTION_CLASSES =
            location -> !location.toString().contains("/classes/java/conformanceTest/");
    private static final Set<String> MODULES =
            Set.of(
                    "tenancy",
                    "iam",
                    "academic",
                    "people",
                    "questionbank",
                    "authoring",
                    "examaccess",
                    "delivery",
                    "grading",
                    "result",
                    "correction",
                    "notification",
                    "shared",
                    "platform",
                    "audit",
                    "outbox");

    @Test
    void modulithDescriptorsAreValid() {
        ApplicationModules.of(CbtPlatformApplication.class, PRODUCTION_CLASSES).verify();
    }

    @Test
    void crossModuleImportsTargetOnlyPublishedApis() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertCrossModuleImportsTargetOnlyPublishedApis(classes);
    }

    @Test
    void rejectsAnImportOfAnotherModulesDomain() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.academic.domain.r2fixture");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertCrossModuleImportsTargetOnlyPublishedApis(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("R2 module boundary violated:"));
    }

    private static void assertCrossModuleImportsTargetOnlyPublishedApis(
            Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();

        for (JavaClass origin : classes) {
            Optional<String> originModule = moduleOf(origin);
            if (originModule.isEmpty()) {
                continue;
            }
            for (var dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                Optional<String> targetModule = moduleOf(target);
                if (targetModule.isEmpty() || targetModule.equals(originModule)) {
                    continue;
                }
                String apiPackage = BASE_PACKAGE + "." + targetModule.orElseThrow() + ".api";
                if (!target.getPackageName().equals(apiPackage)
                        && !target.getPackageName().startsWith(apiPackage + ".")) {
                    violations.add(
                            "R2 module boundary violated: "
                                    + origin.getName()
                                    + " imports non-api type "
                                    + target.getName()
                                    + " from "
                                    + targetModule.orElseThrow()
                                    + "; only declared module::api dependencies are permitted.");
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static Optional<String> moduleOf(JavaClass javaClass) {
        String prefix = BASE_PACKAGE + ".";
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }
        String relative = packageName.substring(prefix.length());
        int separator = relative.indexOf('.');
        String candidate = separator < 0 ? relative : relative.substring(0, separator);
        return MODULES.contains(candidate) ? Optional.of(candidate) : Optional.empty();
    }
}
