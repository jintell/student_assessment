package org.meldtech.platform.platform.infra.runtime;

import org.springframework.web.server.ServerWebExchange;

final class ManagementRequest {
    private ManagementRequest() {}

    static boolean isProbeOrScrape(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        var address = exchange.getRequest().getLocalAddress();
        return path.startsWith("/health/")
                || (path.equals("/prometheus") && address != null && address.getPort() == 9091);
    }
}
