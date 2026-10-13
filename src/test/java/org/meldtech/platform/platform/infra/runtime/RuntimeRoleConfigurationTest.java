package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RuntimeRoleConfigurationTest {

    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void freezesOneRoleAlongsideEnvironmentProfiles(RuntimeRole role) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(role.profile(), "production");
            context.register(RuntimeRoleConfiguration.class);
            context.refresh();
            assertThat(context.getBean(RuntimeRole.class)).isEqualTo(role);
            context.getEnvironment().setActiveProfiles("local");
            assertThat(context.getBean(RuntimeRole.class)).isEqualTo(role);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidProfiles")
    void rejectsInvalidRolesBeforeInstantiatingApplicationBeans(String[] profiles) {
        var instantiated = new AtomicBoolean();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.register(RuntimeRoleConfiguration.class);
            context.registerBean(
                    "externalListener",
                    Object.class,
                    () -> {
                        instantiated.set(true);
                        return new Object();
                    });
            assertThatIllegalStateException()
                    .isThrownBy(context::refresh)
                    .withMessageContaining("RUNTIME_ROLE_INVALID");
            assertThat(instantiated).isFalse();
        }
    }

    @Test
    void defaultProfileCannotSelectARuntimeRole() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setDefaultProfiles("api");
            context.register(RuntimeRoleConfiguration.class);
            assertThatIllegalStateException()
                    .isThrownBy(context::refresh)
                    .withMessageContaining("RUNTIME_ROLE_INVALID");
        }
    }

    @Test
    void resolvesAfterBootExpandsProfileGroups() {
        var application = new SpringApplication(RuntimeRoleConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        try (var context =
                application.run(
                        "--spring.config.location=optional:classpath:/runtime-role-test-absent.yaml",
                        "--spring.profiles.active=request",
                        "--spring.profiles.group.request=api,production",
                        "--spring.main.banner-mode=off")) {
            assertThat(context.getBean(RuntimeRole.class)).isEqualTo(RuntimeRole.API);
        }
    }

    static Stream<Arguments> invalidProfiles() {
        return Stream.of(
                        new String[] {},
                        new String[] {"production"},
                        new String[] {"api", "worker"},
                        new String[] {"api", "pindist"},
                        new String[] {"worker", "pindist"},
                        new String[] {"api", "worker", "pindist"})
                .map(profiles -> Arguments.of((Object) profiles));
    }
}
