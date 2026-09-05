package org.meldtech.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ContextModuleStructureTests {

    private static final Set<String> CONTEXT_MODULES =
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
                    "notification");

    private static final Set<String> PLATFORM_MODULES =
            Set.of("shared", "platform", "audit", "outbox");

    @Test
    void verifiesExactlyTwelveContextModules() {
        ApplicationModules modules = ApplicationModules.of(CbtPlatformApplication.class);

        modules.verify();
        Set<String> discovered =
                modules.stream()
                        .map(ApplicationModule::getIdentifier)
                        .map(Object::toString)
                        .filter(CONTEXT_MODULES::contains)
                        .collect(Collectors.toUnmodifiableSet());

        assertEquals(CONTEXT_MODULES, discovered);
        assertEquals(12, discovered.size());

        Set<String> discoveredPlatformModules =
                modules.stream()
                        .map(ApplicationModule::getIdentifier)
                        .map(Object::toString)
                        .filter(PLATFORM_MODULES::contains)
                        .collect(Collectors.toUnmodifiableSet());

        assertEquals(PLATFORM_MODULES, discoveredPlatformModules);
        assertEquals(4, discoveredPlatformModules.size());
    }
}
