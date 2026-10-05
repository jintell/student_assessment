package org.meldtech.platform.platform.slice.getConformanceReference;

import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
final class Endpoint implements PolicyProtectedRoute {

    private static final Logger LOGGER = LoggerFactory.getLogger(Endpoint.class);
    static final String ROUTE_ID = "platform.getConformanceReference";
    static final String PATH = "/api/v1/platform/conformance-reference";
    static final String SPAN_NAME = "platform.getConformanceReference";
    private static final RequestTelemetry.RequestMetadata TELEMETRY =
            new RequestTelemetry.RequestMetadata(
                    "platform",
                    "getConformanceReference",
                    RequestTelemetry.Audience.OPERATOR,
                    RequestTelemetry.Operation.READ,
                    RequestTelemetry.RouteClass.STANDARD);

    private final PolicyResolver policyResolver;
    private final Handler handler;
    private final RequestTelemetry requestTelemetry;
    private final RouterFunction<ServerResponse> route;

    Endpoint(PolicyResolver policyResolver, Handler handler, RequestTelemetry requestTelemetry) {
        this.policyResolver = policyResolver;
        this.handler = handler;
        this.requestTelemetry = requestTelemetry;
        route = RouterFunctions.route(RequestPredicates.GET(PATH), this::handle);
    }

    @Override
    public RouteDescriptor descriptor() {
        return RouteDescriptor.tenant(ROUTE_ID, HttpMethod.GET, PATH, "platform");
    }

    @Override
    public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
        return route.route(request);
    }

    @Override
    public void accept(RouterFunctions.Visitor visitor) {
        route.accept(visitor);
    }

    private Mono<ServerResponse> handle(ServerRequest serverRequest) {
        return Mono.deferContextual(
                context ->
                        context.<ActorContext>getOrEmpty(ActorContext.class)
                                .map(this::authorizeAndHandle)
                                .orElseGet(
                                        () ->
                                                ServerResponse.status(HttpStatus.FORBIDDEN)
                                                        .cacheControl(CacheControl.noStore())
                                                        .build()));
    }

    private Mono<ServerResponse> authorizeAndHandle(ActorContext actor) {
        Mono<ServerResponse> handling =
                Mono.defer(() -> policyResolver.evaluate(ROUTE_ID, actor, Request.INSTANCE))
                        .flatMap(
                                decision ->
                                        decision == PolicyDecision.ALLOW
                                                ? success(actor)
                                                : Mono.error(
                                                        new AccessDeniedException(
                                                                "Policy denied the operation")))
                        .doOnSuccess(
                                ignored -> LOGGER.info("Conformance reference slice completed"));
        return Mono.from(requestTelemetry.observe(TELEMETRY, handling));
    }

    private Mono<ServerResponse> success(ActorContext actor) {
        return handler.handle(actor, Request.INSTANCE)
                .flatMap(
                        response ->
                                ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .cacheControl(CacheControl.noStore())
                                        .bodyValue(response));
    }
}
