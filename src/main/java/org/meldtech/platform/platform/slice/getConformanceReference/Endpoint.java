package org.meldtech.platform.platform.slice.getConformanceReference;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.api.RequestCarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Mono;

@Component
final class Endpoint implements PolicyProtectedRoute {

    private static final Logger LOGGER = LoggerFactory.getLogger(Endpoint.class);
    static final String ROUTE_ID = "platform.getConformanceReference";
    static final String PATH = "/api/v1/platform/conformance-reference";
    static final String SPAN_NAME = "platform.getConformanceReference";

    private final PolicyResolver policyResolver;
    private final Handler handler;
    private final ObservationRegistry observationRegistry;
    private final RouterFunction<ServerResponse> route;

    Endpoint(
            PolicyResolver policyResolver,
            Handler handler,
            ObservationRegistry observationRegistry) {
        this.policyResolver = policyResolver;
        this.handler = handler;
        this.observationRegistry = observationRegistry;
        route = RouterFunctions.route(RequestPredicates.GET(PATH), this::handle);
    }

    @Override
    public String routeId() {
        return ROUTE_ID;
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
                        context.<RequestCarrier>getOrEmpty(RequestCarrier.class)
                                .map(this::authorizeAndHandle)
                                .orElseGet(
                                        () ->
                                                ServerResponse.status(HttpStatus.FORBIDDEN)
                                                        .cacheControl(CacheControl.noStore())
                                                        .build()));
    }

    private Mono<ServerResponse> authorizeAndHandle(RequestCarrier carrier) {
        return Mono.defer(() -> policyResolver.evaluate(ROUTE_ID, carrier, Request.INSTANCE))
                .flatMap(
                        decision ->
                                decision == PolicyDecision.ALLOW
                                        ? success(carrier)
                                        : forbidden(carrier.correlationId()))
                .doOnSuccess(ignored -> LOGGER.info("Conformance reference slice completed"))
                .name(SPAN_NAME)
                .tap(
                        Micrometer.observation(
                                observationRegistry,
                                registry -> sliceObservation(registry, carrier)));
    }

    private static Observation sliceObservation(
            ObservationRegistry registry, RequestCarrier carrier) {
        Observation observation =
                Observation.createNotStarted(SPAN_NAME, registry)
                        .contextualName(SPAN_NAME)
                        .lowCardinalityKeyValue("module", "platform")
                        .lowCardinalityKeyValue("slice", "getConformanceReference")
                        .lowCardinalityKeyValue("audience", "operator")
                        .lowCardinalityKeyValue("operation", "READ")
                        .highCardinalityKeyValue("correlationId", carrier.correlationId());
        carrier.actor()
                .ifPresent(
                        actor ->
                                observation.lowCardinalityKeyValue(
                                        "actorType", actor.type().name()));
        carrier.tenantId()
                .ifPresent(
                        tenantId ->
                                observation.highCardinalityKeyValue(
                                        "tenantId", tenantId.value().toString()));
        return observation;
    }

    private Mono<ServerResponse> success(RequestCarrier carrier) {
        return handler.handle(carrier, Request.INSTANCE)
                .flatMap(
                        response ->
                                ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .cacheControl(CacheControl.noStore())
                                        .bodyValue(response));
    }

    private static Mono<ServerResponse> forbidden(String correlationId) {
        return ServerResponse.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .bodyValue(
                        new DeniedProblem(
                                HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED", correlationId));
    }

    private record DeniedProblem(int status, String code, String correlationId) {}
}
