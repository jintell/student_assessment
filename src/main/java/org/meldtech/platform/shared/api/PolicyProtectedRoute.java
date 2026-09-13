package org.meldtech.platform.shared.api;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Application route that declares stable policy and tenant-isolation metadata. */
public interface PolicyProtectedRoute extends RouterFunction<ServerResponse> {

    RouteDescriptor descriptor();

    default String routeId() {
        return descriptor().routeId();
    }
}
