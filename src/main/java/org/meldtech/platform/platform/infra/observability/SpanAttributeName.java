package org.meldtech.platform.platform.infra.observability;

enum SpanAttributeName {
    MODULE("module"),
    SLICE("slice"),
    CORRELATION_ID("correlationId"),
    AUDIENCE("audience"),
    ACTOR_TYPE("actorType"),
    ACTOR_ID("actorId"),
    TENANT_ID("tenantId"),
    HTTP_METHOD("http.request.method"),
    ROUTE_CLASS("routeClass"),
    SAMPLING_PRIORITY("sampling.priority"),
    OPERATION("operation"),
    OUTCOME("outcome"),
    ERROR_CODE("errorCode");

    private final String key;

    SpanAttributeName(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }
}
