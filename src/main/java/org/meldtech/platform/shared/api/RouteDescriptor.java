package org.meldtech.platform.shared.api;

import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpMethod;

/** Stable route-table metadata used by policy and tenant-isolation gates. */
public record RouteDescriptor(
        String routeId,
        HttpMethod method,
        String pathTemplate,
        String owningModule,
        Scope scope,
        Optional<PlatformOperation> platformOperation) {

    public RouteDescriptor {
        routeId = requireText(routeId, "routeId");
        Objects.requireNonNull(method, "method");
        pathTemplate = requireText(pathTemplate, "pathTemplate");
        owningModule = requireText(owningModule, "owningModule");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(platformOperation, "platformOperation");
        if ((scope == Scope.PLATFORM) != platformOperation.isPresent()) {
            throw new IllegalArgumentException(
                    "Platform routes require one operation and tenant routes require none");
        }
    }

    public static RouteDescriptor tenant(
            String routeId, HttpMethod method, String pathTemplate, String owningModule) {
        return new RouteDescriptor(
                routeId, method, pathTemplate, owningModule, Scope.TENANT, Optional.empty());
    }

    public static RouteDescriptor platform(
            String routeId,
            HttpMethod method,
            String pathTemplate,
            String owningModule,
            PlatformOperation operation) {
        return new RouteDescriptor(
                routeId,
                method,
                pathTemplate,
                owningModule,
                Scope.PLATFORM,
                Optional.of(operation));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Scope {
        TENANT,
        PLATFORM
    }
}
