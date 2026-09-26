package org.meldtech.platform.platform.infra.idempotency;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.shared.api.IdempotencyMechanism;
import org.meldtech.platform.shared.api.IdempotencyPolicy;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.springframework.http.HttpMethod;

public final class IdempotencyRoutePolicyValidator {

    private static final Set<HttpMethod> SAFE_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

    private IdempotencyRoutePolicyValidator() {}

    public static void verify(Collection<? extends PolicyProtectedRoute> routes) {
        Objects.requireNonNull(routes, "routes").forEach(IdempotencyRoutePolicyValidator::verify);
    }

    private static void verify(PolicyProtectedRoute route) {
        RouteDescriptor descriptor = Objects.requireNonNull(route, "route").descriptor();
        IdempotencyPolicy policy = route.getClass().getAnnotation(IdempotencyPolicy.class);
        if (policy == null) {
            if (!SAFE_METHODS.contains(descriptor.method())) {
                fail(descriptor, "state-changing route has no @IdempotencyPolicy declaration");
            }
            return;
        }

        String protection = policy.databaseProtection();
        if (!protection.equals(protection.trim())) {
            fail(descriptor, "databaseProtection must not have surrounding whitespace");
        }
        IdempotencyMechanism mechanism = policy.mechanism();
        switch (mechanism) {
            case REDIS_HEADER -> {
                if (!descriptor.method().equals(HttpMethod.POST)
                        || policy.createsDurableRecord()
                        || !protection.isEmpty()) {
                    fail(
                            descriptor,
                            "REDIS_HEADER requires POST, createsDurableRecord=false, and no databaseProtection");
                }
            }
            case POSTGRES_UNIQUE -> {
                if (!policy.createsDurableRecord() || protection.isBlank()) {
                    fail(
                            descriptor,
                            "POSTGRES_UNIQUE requires createsDurableRecord=true and a named "
                                    + "PostgreSQL constraint or index");
                }
            }
            case DURABLE_STATE_GUARD -> {
                if (protection.isBlank()) {
                    fail(
                            descriptor,
                            "DURABLE_STATE_GUARD requires a named table, transition, or deduplication guard");
                }
            }
            case NOT_APPLICABLE -> {
                if (policy.createsDurableRecord() || !protection.isEmpty()) {
                    fail(
                            descriptor,
                            "NOT_APPLICABLE cannot declare a durable record or database protection");
                }
            }
        }
    }

    private static void fail(RouteDescriptor descriptor, String detail) {
        throw new IllegalStateException(
                "IDEMPOTENCY-ROUTE: " + descriptor.routeId() + " " + detail);
    }
}
