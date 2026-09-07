package org.meldtech.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ContextModuleDependencyTests {

    private static final ImportOption PRODUCTION_CLASSES =
            location -> !location.toString().contains("/classes/java/conformanceTest/");

    private static final Map<String, Set<String>> EXPECTED_DEPENDENCIES =
            Map.ofEntries(
                    Map.entry("tenancy", Set.of("shared::api", "audit::api", "outbox::api")),
                    Map.entry(
                            "iam",
                            Set.of("tenancy::api", "shared::api", "audit::api", "outbox::api")),
                    Map.entry("academic", Set.of("tenancy::api", "shared::api", "audit::api")),
                    Map.entry(
                            "people",
                            Set.of("tenancy::api", "shared::api", "audit::api", "outbox::api")),
                    Map.entry("questionbank", Set.of("tenancy::api", "shared::api", "audit::api")),
                    Map.entry(
                            "authoring",
                            Set.of(
                                    "tenancy::api",
                                    "iam::api",
                                    "academic::api",
                                    "people::api",
                                    "questionbank::api",
                                    "shared::api",
                                    "audit::api",
                                    "outbox::api")),
                    Map.entry(
                            "examaccess",
                            Set.of(
                                    "tenancy::api",
                                    "iam::api",
                                    "people::api",
                                    "authoring::api",
                                    "delivery::api",
                                    "shared::api",
                                    "audit::api",
                                    "outbox::api")),
                    Map.entry(
                            "delivery",
                            Set.of(
                                    "tenancy::api",
                                    "authoring::api",
                                    "shared::api",
                                    "audit::api",
                                    "outbox::api")),
                    Map.entry(
                            "grading",
                            Set.of(
                                    "tenancy::api",
                                    "authoring::api",
                                    "delivery::api",
                                    "shared::api",
                                    "audit::api",
                                    "outbox::api")),
                    Map.entry(
                            "result",
                            Set.of(
                                    "tenancy::api",
                                    "iam::api",
                                    "people::api",
                                    "grading::api",
                                    "shared::api",
                                    "audit::api",
                                    "outbox::api")),
                    Map.entry(
                            "correction",
                            Set.of(
                                    "tenancy::api",
                                    "iam::api",
                                    "result::api",
                                    "shared::api",
                                    "audit::api",
                                    "outbox::api")),
                    Map.entry(
                            "notification",
                            Set.of(
                                    "tenancy::api",
                                    "people::api",
                                    "examaccess::api",
                                    "result::api",
                                    "correction::api",
                                    "shared::api",
                                    "audit::api",
                                    "outbox::api")));

    private static final Set<String> ALL_MODULES =
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
    void descriptorsMatchThePermittedDependencyMatrixExactly() throws ClassNotFoundException {
        ApplicationModules.of(CbtPlatformApplication.class, PRODUCTION_CLASSES).verify();

        for (Map.Entry<String, Set<String>> expected : EXPECTED_DEPENDENCIES.entrySet()) {
            Package modulePackage =
                    Class.forName("org.meldtech.platform." + expected.getKey() + ".package-info")
                            .getPackage();
            ApplicationModule descriptor =
                    Objects.requireNonNull(
                            modulePackage.getAnnotation(ApplicationModule.class),
                            () -> expected.getKey() + " has no ApplicationModule descriptor");
            Set<String> actual =
                    Arrays.stream(descriptor.allowedDependencies()).collect(Collectors.toSet());

            assertEquals(expected.getValue(), actual, expected.getKey() + " dependency matrix");
            assertTrue(
                    actual.stream()
                            .map(dependency -> dependency.substring(0, dependency.indexOf("::")))
                            .allMatch(ALL_MODULES::contains),
                    expected.getKey() + " declares an unknown module dependency");
        }
    }
}
