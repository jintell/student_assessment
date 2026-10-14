package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class RuntimeDrainStateTest {

    @Test
    void markerLatchesReadinessDownWithoutExposingDetails(@TempDir Path directory)
            throws Exception {
        Path marker = directory.resolve("draining");
        try (var context = new GenericApplicationContext()) {
            var states = new ArrayList<Object>();
            context.addApplicationListener(
                    event -> {
                        if (event instanceof AvailabilityChangeEvent<?> change) {
                            states.add(change.getState());
                        }
                    });
            context.refresh();
            var state =
                    new RuntimeDrainState(
                            context,
                            new MockEnvironment()
                                    .withProperty("cbt.runtime.drain-marker", marker.toString()));
            try {
                StepVerifier.create(state.health())
                        .assertNext(health -> assertThat(health.getStatus()).isEqualTo(Status.UP))
                        .verifyComplete();
                Files.createFile(marker);
                StepVerifier.create(state.health())
                        .assertNext(
                                health -> {
                                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                                    assertThat(health.getDetails()).isEmpty();
                                })
                        .verifyComplete();
                Files.delete(marker);
                StepVerifier.create(state.health())
                        .assertNext(health -> assertThat(health.getStatus()).isEqualTo(Status.DOWN))
                        .verifyComplete();
                assertThat(states).containsExactly(ReadinessState.REFUSING_TRAFFIC);
            } finally {
                state.destroy();
            }
        }
    }

    @Test
    void sigtermRejectsNewAdmissionButLetsAdmittedWorkComplete() {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var state = new RuntimeDrainState(context, new MockEnvironment());
            var filter = new DrainAdmissionFilter(state);
            var body = Sinks.<Void>empty();
            var exchange =
                    MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/reference"));
            try {
                StepVerifier.create(filter.filter(exchange, ignored -> body.asMono()))
                        .then(
                                () -> {
                                    state.onApplicationEvent(new ContextClosedEvent(context));
                                    StepVerifier.create(
                                                    filter.filter(
                                                            exchange,
                                                            ignored ->
                                                                    Mono.error(
                                                                            new AssertionError(
                                                                                    "admitted"))))
                                            .expectError(RuntimeUnavailableException.class)
                                            .verify();
                                    assertThat(
                                                    exchange.getResponse()
                                                            .getHeaders()
                                                            .getFirst("Retry-After"))
                                            .isEqualTo("1");
                                    body.tryEmitEmpty();
                                })
                        .verifyComplete();
                var probe = MockServerWebExchange.from(MockServerHttpRequest.get("/health/ready"));
                StepVerifier.create(filter.filter(probe, ignored -> Mono.empty())).verifyComplete();
            } finally {
                state.destroy();
            }
        }
    }
}
