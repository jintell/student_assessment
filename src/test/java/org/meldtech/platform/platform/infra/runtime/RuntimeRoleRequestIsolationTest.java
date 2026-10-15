package org.meldtech.platform.platform.infra.runtime;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

class RuntimeRoleRequestIsolationTest {

    @Test
    void requestInputCannotChangeTheBootSelectedRole() {
        new ReactiveWebApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=api")
                .withUserConfiguration(RuntimeRoleConfiguration.class, RoleProbeConfiguration.class)
                .run(
                        context ->
                                WebTestClient.bindToApplicationContext(context)
                                        .build()
                                        .post()
                                        .uri(
                                                uriBuilder ->
                                                        uriBuilder
                                                                .path("/role-probe")
                                                                .queryParam("role", "worker")
                                                                .queryParam(
                                                                        "spring.profiles.active",
                                                                        "pindist")
                                                                .build())
                                        .header("X-Runtime-Role", "worker")
                                        .header("Spring-Profiles-Active", "pindist")
                                        .cookie("runtimeRole", "worker")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(
                                                """
                                                {
                                                  "role": "worker",
                                                  "profile": "pindist",
                                                  "spring.profiles.active": "worker"
                                                }
                                                """)
                                        .exchange()
                                        .expectStatus()
                                        .isOk()
                                        .expectBody(String.class)
                                        .isEqualTo(RuntimeRole.API.name()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class RoleProbeConfiguration {

        @Bean
        RouterFunction<ServerResponse> roleProbe(RuntimeRole role) {
            return route(
                    POST("/role-probe"), request -> ServerResponse.ok().bodyValue(role.name()));
        }
    }
}
