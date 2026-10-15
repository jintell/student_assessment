package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoleManifest.Route;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PindistRouteValidatorTest {
    @Test
    void enforcesDeliveryStatusAndExactRouteTuple() {
        var reserved = new Route("GET", PindistRouteValidator.PATH);
        PindistRouteValidator.validate(List.of(), false);
        PindistRouteValidator.validate(List.of(reserved), true);
        assertThatIllegalStateException()
                .isThrownBy(() -> PindistRouteValidator.validate(List.of(), true));
        for (List<Route> invalid :
                List.of(
                        List.of(reserved),
                        List.of(reserved, reserved),
                        List.of(new Route("HEAD", PindistRouteValidator.PATH)),
                        List.of(new Route("GET", "/exam-sessions/{id}/pin-distribution")),
                        List.of(new Route("GET", "/health/ready")),
                        List.of(new Route("GET", "/api/v1/**")))) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> PindistRouteValidator.validate(invalid, false));
            if (!invalid.equals(List.of(reserved))) {
                assertThatIllegalStateException()
                        .isThrownBy(() -> PindistRouteValidator.validate(invalid, true));
            }
        }
    }

    @Test
    void inspectsActualWebFluxMappingsAndRejectsUnknownMappingTypes() {
        runner().run(context -> assertThat(context).hasNotFailed());
        runner().withUserConfiguration(UnexpectedRoute.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("pindist business route allowlist");
                        });
        runner().withBean("opaqueMapping", HandlerMapping.class, () -> exchange -> Mono.empty())
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsCustomEmptyMappingWithFrameworkSimpleName() {
        runner().withBean("lookalikeMapping", HandlerMapping.class, EmptyHandlerMapping::new)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("pindist business route allowlist");
                        });
    }

    @Test
    void rejectsRegisteredStaticResources() {
        runner().withUserConfiguration(StaticResources.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("pindist business route allowlist");
                        });
    }

    @Test
    void rejectsUnknownPathsAndMethodsBeforeCallingSecurity() {
        var guard = new PindistRequestGuard(true);
        for (String path :
                List.of(
                        "/health/ready",
                        "/actuator/health",
                        "/arbitrary",
                        "/exam-sessions/id/pin-distribution",
                        "/api/v1/exam-sessions/id/pin-distribution.json",
                        "/api/v1/exam-sessions/id/pin-distribution/",
                        "/api/v1/exam-sessions//pin-distribution",
                        "/api/v1/exam-sessions/a%2Fb/pin-distribution",
                        "/api/v1/exam-sessions/a%252Fb/pin-distribution",
                        "/api/v1/exam-sessions/a;ignored/pin-distribution")) {
            assertRejected(guard, HttpMethod.GET, path);
        }
        for (HttpMethod method :
                List.of(HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.POST, HttpMethod.DELETE)) {
            assertRejected(guard, method, "/api/v1/exam-sessions/id/pin-distribution");
        }
        assertRejected(
                new PindistRequestGuard(false),
                HttpMethod.GET,
                "/api/v1/exam-sessions/id/pin-distribution");
    }

    @Test
    void allowedRouteStillRunsSecurityAndCannotBeEnabledThroughRequestInput() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get(
                                        "/api/v1/exam-sessions/id/pin-distribution?profile=api")
                                .header("X-Runtime-Role", "api")
                                .build());
        StepVerifier.create(
                        new PindistRequestGuard(true)
                                .filter(
                                        exchange,
                                        next -> {
                                            next.getResponse()
                                                    .setStatusCode(HttpStatus.UNAUTHORIZED);
                                            return next.getResponse().setComplete();
                                        }))
                .verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertRejected(
                new PindistRequestGuard(false),
                HttpMethod.GET,
                "/api/v1/exam-sessions/id/pin-distribution?shippedFeatures=FEAT-EXAM-003");
    }

    @Test
    void deliveryStatusIsStrictAndTheReservedFeatureIsNotShipped() {
        assertThat(FeatureDeliveryStatus.load()).doesNotContain(PindistRouteValidator.FEATURE);
        for (String yaml :
                List.of(
                        "{}",
                        "shippedFeatures: [BAD]",
                        "shippedFeatures: [FEAT-EXAM-003, FEAT-EXAM-003]",
                        "shippedFeatures: []\nshippedFeatures: []",
                        "shippedFeatures: []\nunknown: true")) {
            assertThatIllegalStateException()
                    .isThrownBy(
                            () ->
                                    FeatureDeliveryStatus.read(
                                            new ByteArrayInputStream(
                                                    yaml.getBytes(StandardCharsets.UTF_8))));
        }
    }

    private static void assertRejected(PindistRequestGuard guard, HttpMethod method, String path) {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.method(method, URI.create(path)).build());
        AtomicBoolean securityCalled = new AtomicBoolean();
        StepVerifier.create(
                        guard.filter(
                                exchange,
                                next -> {
                                    securityCalled.set(true);
                                    return Mono.empty();
                                }))
                .verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(securityCalled).isFalse();
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=pindist")
                .withUserConfiguration(WebConfiguration.class, PindistRouteValidator.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebConfiguration {}

    static final class EmptyHandlerMapping implements HandlerMapping {
        @Override
        public Mono<Object> getHandler(ServerWebExchange exchange) {
            return Mono.empty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StaticResources implements WebFluxConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/assets/**").addResourceLocations("classpath:/static/");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnexpectedRoute {
        @Bean
        RouterFunction<ServerResponse> extra() {
            return RouterFunctions.route()
                    .GET("/unexpected", request -> ServerResponse.ok().build())
                    .build();
        }
    }
}
