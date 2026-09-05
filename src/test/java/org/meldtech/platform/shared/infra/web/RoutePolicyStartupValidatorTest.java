package org.meldtech.platform.shared.infra.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RequestCarrier;
import org.meldtech.platform.shared.api.SlicePolicy;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

class RoutePolicyStartupValidatorTest {

    @Test
    void acceptsExactlyOnePolicyForEveryRoute() {
        RoutePolicyStartupValidator validator =
                new RoutePolicyStartupValidator(
                        List.of(new TestRoute("route")), List.of(policy("route")));

        assertDoesNotThrow(validator::afterSingletonsInstantiated);
    }

    @Test
    void rejectsARouteWithoutAPolicy() {
        RoutePolicyStartupValidator validator =
                new RoutePolicyStartupValidator(List.of(new TestRoute("route")), List.of());

        assertThrows(IllegalStateException.class, validator::afterSingletonsInstantiated);
    }

    @Test
    void rejectsDuplicatePolicies() {
        RoutePolicyStartupValidator validator =
                new RoutePolicyStartupValidator(
                        List.of(new TestRoute("route")), List.of(policy("route"), policy("route")));

        assertThrows(IllegalStateException.class, validator::afterSingletonsInstantiated);
    }

    @Test
    void rejectsAnOrphanPolicy() {
        RoutePolicyStartupValidator validator =
                new RoutePolicyStartupValidator(List.of(), List.of(policy("orphan")));

        assertThrows(IllegalStateException.class, validator::afterSingletonsInstantiated);
    }

    @Test
    void rejectsAnUnclassifiedRouterFunction() {
        RouterFunction<ServerResponse> route =
                RouterFunctions.route()
                        .GET("/route", ignored -> ServerResponse.ok().build())
                        .build();
        RoutePolicyStartupValidator validator =
                new RoutePolicyStartupValidator(List.of(route), List.of());

        assertThrows(IllegalStateException.class, validator::afterSingletonsInstantiated);
    }

    private static SlicePolicy<String> policy(String routeId) {
        return new SlicePolicy<>() {
            @Override
            public String routeId() {
                return routeId;
            }

            @Override
            public Mono<PolicyDecision> evaluate(RequestCarrier carrier, String request) {
                return Mono.just(PolicyDecision.DENY);
            }
        };
    }

    private record TestRoute(String routeId) implements PolicyProtectedRoute {

        @Override
        public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return Mono.empty();
        }
    }
}
