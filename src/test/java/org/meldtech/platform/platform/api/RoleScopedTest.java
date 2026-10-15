package org.meldtech.platform.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RoleScopedTest {

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void selectsTypesAndFactoryMethodsForEachRole(RuntimeRole role) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(role.profile(), "production");
            context.register(WorkerComponent.class, Factory.class);
            context.refresh();

            assertThat(context.getBeansOfType(WorkerComponent.class))
                    .hasSize(role == RuntimeRole.WORKER ? 1 : 0);
            assertThat(context.getBeansOfType(String.class))
                    .hasSize(role == RuntimeRole.WORKER ? 0 : 1);
            assertThat(RuntimeRole.fromProfile(role.profile())).isEqualTo(role);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"API", " api", "migration", "worker | api", "!worker", "${role}"})
    void rejectsNamesOutsideTheClosedVocabulary(String profile) {
        assertThatIllegalArgumentException().isThrownBy(() -> RuntimeRole.fromProfile(profile));
    }

    @Test
    void exposesExactlyTheThreeRuntimeRoles() {
        assertThat(RuntimeRole.values())
                .containsExactly(RuntimeRole.API, RuntimeRole.WORKER, RuntimeRole.PINDIST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "production", "migration"})
    void excludesRoleScopedBeansWithoutAnActiveRole(String profile) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.register(WorkerComponent.class, Factory.class);
            context.refresh();

            assertThat(context.getBeansOfType(WorkerComponent.class)).isEmpty();
            assertThat(context.getBeansOfType(String.class)).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void rejectsInvalidDeclarationsEvenAfterAMatchingRole(Class<?> component) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("worker", "production");

            assertThatIllegalArgumentException().isThrownBy(() -> context.register(component));
        }
    }

    static Class<?>[] invalidDeclarations() {
        return new Class<?>[] {
            EmptyRoles.class,
            DuplicateRoles.class,
            UnknownRole.class,
            EnvironmentRole.class,
            ExpressionRole.class,
            NegatedRole.class,
            PlaceholderRole.class
        };
    }

    @RoleScoped({})
    static class EmptyRoles {}

    @RoleScoped({"worker", "worker"})
    static class DuplicateRoles {}

    @RoleScoped({"worker", "unknown"})
    static class UnknownRole {}

    @RoleScoped({"worker", "production"})
    static class EnvironmentRole {}

    @RoleScoped({"worker", "worker | api"})
    static class ExpressionRole {}

    @RoleScoped({"worker", "!api"})
    static class NegatedRole {}

    @RoleScoped({"worker", "${role}"})
    static class PlaceholderRole {}

    @RoleScoped("worker")
    static class WorkerComponent {}

    @Configuration(proxyBeanMethods = false)
    static class Factory {
        @Bean
        @RoleScoped({"api", "pindist"})
        String requestComponent() {
            return "request";
        }
    }
}
