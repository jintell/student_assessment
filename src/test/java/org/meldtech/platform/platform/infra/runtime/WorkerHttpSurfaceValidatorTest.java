package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
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
    private static final String EXCLUDED_AUTO_CONFIGURATIONS =
            String.join(
                    ",",
                    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration",
                    "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration",
                    "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration",
                    "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration");
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
    void explicitlyEnabledScrapeIsTheOnlyAdditionalManagementSurface() {
        runner().withPropertyValues(
                        "management.endpoint.prometheus.enabled=true",
                        "management.endpoints.web.exposure.include=health,prometheus")
                .run(context -> assertThat(context).hasNotFailed());
        runner().withPropertyValues(
                        "management.endpoint.prometheus.enabled=true",
                        "management.endpoints.web.exposure.include=health,prometheus,env")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void workerServesProbesOnManagementPortAndNoBusinessHttp() throws Exception {
        try (var context =
                new SpringApplicationBuilder(WorkerProbeApplication.class)
                        .web(WebApplicationType.REACTIVE)
                        .properties(
                                "spring.config.location=optional:classpath:/worker-probe-test.properties",
                                "spring.profiles.active=worker",
                                "spring.autoconfigure.exclude=" + EXCLUDED_AUTO_CONFIGURATIONS,
                                "server.port=0",
                                "spring.web.resources.add-mappings=false",
                                "management.server.port=9091",
                                "management.endpoints.enabled-by-default=false",
                                "management.endpoints.web.base-path=/",
                                "management.endpoints.web.path-mapping.health=health",
                                "management.endpoints.web.exposure.include=health",
                                "management.endpoints.web.discovery.enabled=false",
                                "management.endpoint.health.enabled=true",
                                "management.endpoint.health.show-details=never",
                                "management.endpoint.health.show-components=never",
                                "management.endpoint.health.group.live.include=ping",
                                "management.endpoint.health.group.ready.include=ping")
                        .run()) {
            assertThat(BusinessRouteInventory.inspect(context)).isEmpty();
            assertProbeResponds("/health/live");
            assertProbeResponds("/health/ready");
        }
    }

    private static void assertProbeResponds(String path) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:9091" + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void managementScrapeRequiresAuthenticationAndExportsCachedMeters() throws Exception {
        try (var context =
                new SpringApplicationBuilder(
                                WorkerProbeApplication.class, WorkerWebServerConfiguration.class)
                        .web(WebApplicationType.REACTIVE)
                        .properties(
                                "spring.config.location=optional:classpath:/worker-probe-test.properties",
                                "spring.profiles.active=worker",
                                "spring.autoconfigure.exclude=" + EXCLUDED_AUTO_CONFIGURATIONS,
                                "server.port=-1",
                                "management.server.port=9091",
                                "spring.web.resources.add-mappings=false",
                                "management.endpoints.enabled-by-default=false",
                                "management.endpoints.web.base-path=/",
                                "management.endpoints.web.exposure.include=health,prometheus",
                                "management.endpoint.health.enabled=true",
                                "management.endpoint.prometheus.enabled=true",
                                "spring.security.user.name=metrics-test",
                                "spring.security.user.password=local-test-only")
                        .run()) {
            var registry = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            registry.counter("scrape.test").increment();
            var uri = URI.create("http://127.0.0.1:9091/prometheus");
            var client = HttpClient.newHttpClient();
            assertThat(
                            client.send(
                                            HttpRequest.newBuilder(uri).GET().build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                    .isEqualTo(401);
            String credentials =
                    java.util.Base64.getEncoder()
                            .encodeToString(
                                    "metrics-test:local-test-only"
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var response =
                    client.send(
                            HttpRequest.newBuilder(uri)
                                    .header("Authorization", "Basic " + credentials)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("scrape_test_total 1.0");
            assertThat(BusinessRouteInventory.inspect(context)).isEmpty();
            assertThat(
                            java.util.Objects.requireNonNull(
                                            ((org.springframework.boot.web.server.context
                                                                    .WebServerApplicationContext)
                                                            context)
                                                    .getWebServer())
                                    .getPort())
                    .isEqualTo(-1);
        }
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
    @EnableAutoConfiguration
    static class WorkerProbeApplication {}

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
