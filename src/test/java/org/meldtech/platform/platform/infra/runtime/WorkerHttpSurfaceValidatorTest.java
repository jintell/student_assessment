package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

class WorkerHttpSurfaceValidatorTest {
    private static final List<String> VALID_CONFIGURATION =
            List.of(
                    "spring.profiles.active=worker",
                    "server.port=-1",
                    "management.server.port=9091",
                    "management.endpoints.enabled-by-default=false",
                    "management.endpoints.web.base-path=/",
                    "management.endpoints.web.path-mapping.health=health",
                    "management.endpoints.web.exposure.include=health",
                    "management.endpoints.web.discovery.enabled=false",
                    "management.endpoint.health.enabled=true",
                    "management.endpoint.health.show-details=never",
                    "management.endpoint.health.show-components=never");

    @Test
    void workerStartsWithManagementOnlyConfigurationAndNoBusinessRoutes() {
        runner().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void applicationConfigurationDisablesTheWorkerBusinessListener(@TempDir Path temporary) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=worker",
                        "CBT_BROKER_SECRETS_PATH=" + temporary.toAbsolutePath() + "/")
                .run(
                        context -> {
                            assertThat(context.getEnvironment().getProperty("server.port"))
                                    .isEqualTo("-1");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("management.server.port"))
                                    .isEqualTo("9091");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "management.endpoints.web.exposure.include"))
                                    .isEqualTo("health");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "management.endpoint.health.show-details"))
                                    .isEqualTo("never");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "management.endpoint.health.show-components"))
                                    .isEqualTo("never");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "management.endpoint.health.group.live.include"))
                                    .isEqualTo("livenessState,ping,runtimeMemory");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty(
                                                            "management.endpoint.health.group.ready.include"))
                                    .isEqualTo("readinessState,ping,runtimeDrain");
                        });
    }

    @Test
    void businessAndOpaqueMappingsFailBeforeLifecycleActivation() {
        runner().withUserConfiguration(UnexpectedRoute.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("worker business routes must be empty");
                        });
        runner().withBean("opaqueMapping", HandlerMapping.class, () -> exchange -> Mono.empty())
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("opaque mapping");
                        });
    }

    @Test
    void anyListenerOrProbeConfigurationDriftFailsBoot() {
        for (String invalid :
                List.of(
                        "server.port=8080",
                        "management.server.port=8080",
                        "management.endpoints.enabled-by-default=true",
                        "management.endpoints.web.base-path=/actuator",
                        "management.endpoints.web.path-mapping.health=status",
                        "management.endpoints.web.exposure.include=health,metrics",
                        "management.endpoints.web.discovery.enabled=true",
                        "management.endpoint.health.enabled=false",
                        "management.endpoint.health.show-details=always",
                        "management.endpoint.health.show-components=always")) {
            runner().withPropertyValues(invalid)
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                assertThat(context.getStartupFailure())
                                        .hasMessageContaining("worker HTTP surface");
                            });
        }
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withPropertyValues(VALID_CONFIGURATION.toArray(String[]::new))
                .withUserConfiguration(WebConfiguration.class, WorkerHttpSurfaceValidator.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebConfiguration {}

    @Configuration(proxyBeanMethods = false)
    static class UnexpectedRoute {
        @Bean
        RouterFunction<ServerResponse> route() {
            return RouterFunctions.route()
                    .GET("/unexpected", request -> ServerResponse.ok().build())
                    .build();
        }
    }
}
