package org.meldtech.platform.platform.infra.runtime;

import org.meldtech.platform.platform.api.RoleScoped;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.HttpHandler;

@Configuration(proxyBeanMethods = false)
@RoleScoped("worker")
class WorkerWebServerConfiguration {

    @Bean
    NettyReactiveWebServerFactory workerWebServerFactory() {
        return new WorkerWebServerFactory();
    }

    static final class WorkerWebServerFactory extends NettyReactiveWebServerFactory {
        @Override
        public WebServer getWebServer(HttpHandler httpHandler) {
            if (getPort() != -1) {
                return super.getWebServer(httpHandler);
            }
            return new WebServer() {
                @Override
                public void start() {}

                @Override
                public void stop() {}

                @Override
                public int getPort() {
                    return -1;
                }
            };
        }
    }
}
