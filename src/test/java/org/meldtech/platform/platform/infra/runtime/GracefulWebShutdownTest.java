package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Sinks;

class GracefulWebShutdownTest {
    @Test
    void readinessDropsBeforeNettyWaitsForAnAdmittedResponse() throws Exception {
        var admitted = new CountDownLatch(1);
        var drained = new CountDownLatch(1);
        var responseBody = Sinks.<String>one();
        var factory = new NettyReactiveWebServerFactory(0);
        factory.setShutdown(Shutdown.GRACEFUL);
        var server =
                factory.getWebServer(
                        (request, response) -> {
                            admitted.countDown();
                            return response.writeWith(
                                    responseBody
                                            .asMono()
                                            .map(
                                                    body ->
                                                            response.bufferFactory()
                                                                    .wrap(
                                                                            body.getBytes(
                                                                                    java.nio.charset
                                                                                            .StandardCharsets
                                                                                            .UTF_8))));
                        });
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var state = new RuntimeDrainState(context, new MockEnvironment());
            try {
                server.start();
                var response =
                        WebClient.create("http://127.0.0.1:" + server.getPort())
                                .get()
                                .retrieve()
                                .bodyToMono(String.class)
                                .toFuture();
                assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
                state.requestDrain();
                assertThat(state.isDraining()).isTrue();
                server.shutDownGracefully(result -> drained.countDown());
                assertThat(drained.await(50, TimeUnit.MILLISECONDS)).isFalse();
                responseBody.tryEmitValue("finished");
                assertThat(response.get(5, TimeUnit.SECONDS)).isEqualTo("finished");
                assertThat(drained.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                responseBody.tryEmitEmpty();
                server.stop();
                state.destroy();
            }
        }
    }
}
