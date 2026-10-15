package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.EndpointMapping;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.actuate.endpoint.web.AdditionalHealthEndpointPathsWebFluxHandlerMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

class RuntimeAdmissionConfigurationTest {
    private final ReactiveWebApplicationContextRunner contexts =
            new ReactiveWebApplicationContextRunner()
                    .withUserConfiguration(RuntimeAdmissionConfiguration.class)
                    .withPropertyValues("spring.profiles.active=api");

    @Test
    void matchesARegisteredRouteBeforeSelectingItsPolicy() {
        contexts.withUserConfiguration(ReviewedRoute.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            WebTestClient.bindToApplicationContext(context)
                                    .build()
                                    .get()
                                    .uri("/api/v1/platform/conformance-reference")
                                    .exchange()
                                    .expectStatus()
                                    .isOk()
                                    .expectBody(String.class)
                                    .isEqualTo("ADMINISTRATIVE");
                        });
    }

    @Test
    void emptyBootHealthMappingDoesNotBecomeABusinessRoute() {
        contexts.withUserConfiguration(ReviewedRoute.class)
                .withBean(
                        AdditionalHealthEndpointPathsWebFluxHandlerMapping.class,
                        () ->
                                new AdditionalHealthEndpointPathsWebFluxHandlerMapping(
                                        new EndpointMapping("/"), null, Set.of()))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void missingRouteClassificationFailsStartup() {
        contexts.withUserConfiguration(UnreviewedRoute.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class ReviewedRoute {
        @Bean
        RouterFunction<ServerResponse> reference() {
            return route(
                    GET("/api/v1/platform/conformance-reference"),
                    request ->
                            ServerResponse.ok()
                                    .body(
                                            Mono.deferContextual(
                                                    context -> {
                                                        RequestAdmission admission =
                                                                context.get(RequestAdmission.class);
                                                        return Mono.just(
                                                                admission
                                                                        .policy()
                                                                        .orElseThrow()
                                                                        .workload()
                                                                        .name());
                                                    }),
                                            String.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class UnreviewedRoute {
        @Bean
        RouterFunction<ServerResponse> unknown() {
            return route(GET("/unreviewed"), request -> ServerResponse.ok().build());
        }
    }
}
