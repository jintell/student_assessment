package org.meldtech.platform.shared.api;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Application route that declares the stable identifier used for one-to-one policy resolution. */
public interface PolicyProtectedRoute extends RouterFunction<ServerResponse> {

    String routeId();
}
