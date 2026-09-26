package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.infra.idempotency.IdempotencyRoutePolicyValidator;
import org.meldtech.platform.shared.api.IdempotencyMechanism;
import org.meldtech.platform.shared.api.IdempotencyPolicy;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

class IdempotencyRouteRuleTests {

    @Test
    void productionRouteTableSatisfiesTheIdempotencyRule() {
        assertDoesNotThrow(
                () ->
                        IdempotencyRoutePolicyValidator.verify(
                                List.of(new SafeReadRoute(), new ProtectedCreateRoute())));
    }

    @Test
    void rejectsDurableRecordsProtectedByRedisHeader() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                IdempotencyRoutePolicyValidator.verify(
                                        List.of(new InvalidRedisRoute())));

        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("IDEMPOTENCY-ROUTE: platform.invalidRedis"));
        assertTrue(message.contains("createsDurableRecord=false"));
    }

    @Test
    void rejectsStateChangingRouteWithoutADeclaration() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                IdempotencyRoutePolicyValidator.verify(
                                        List.of(new UndeclaredWriteRoute())));

        assertTrue(String.valueOf(failure.getMessage()).contains("platform.undeclaredWrite"));
    }

    private abstract static class StubRoute implements PolicyProtectedRoute {

        @Override
        public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return Mono.empty();
        }

        @Override
        public void accept(RouterFunctions.Visitor visitor) {}
    }

    private static final class SafeReadRoute extends StubRoute {

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant("platform.read", HttpMethod.GET, "/read", "platform");
        }
    }

    @IdempotencyPolicy(
            createsDurableRecord = true,
            mechanism = IdempotencyMechanism.POSTGRES_UNIQUE,
            databaseProtection = "uq_operation_business_key")
    private static final class ProtectedCreateRoute extends StubRoute {

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant(
                    "platform.protectedCreate", HttpMethod.POST, "/create", "platform");
        }
    }

    @IdempotencyPolicy(createsDurableRecord = true, mechanism = IdempotencyMechanism.REDIS_HEADER)
    private static final class InvalidRedisRoute extends StubRoute {

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant(
                    "platform.invalidRedis", HttpMethod.POST, "/invalid", "platform");
        }
    }

    private static final class UndeclaredWriteRoute extends StubRoute {

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant(
                    "platform.undeclaredWrite", HttpMethod.PATCH, "/write", "platform");
        }
    }
}
