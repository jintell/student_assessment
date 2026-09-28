package org.meldtech.platform.platform.infra.idempotency;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

class StoredResponseReplayerTest {

    @Test
    void replaysOnlyThePreviouslyStoredResponse() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/operations"));
        StoredResponse stored =
                new StoredResponse(
                        HttpStatus.CREATED.value(),
                        Map.of("Location", "/operations/42"),
                        "application/json",
                        "{\"status\":\"accepted\"}".getBytes(UTF_8));

        StepVerifier.create(new StoredResponseReplayer().replay(exchange, stored)).verifyComplete();

        assertEquals(HttpStatus.CREATED, exchange.getResponse().getStatusCode());
        assertEquals("/operations/42", exchange.getResponse().getHeaders().getFirst("Location"));
        assertEquals(
                "{\"status\":\"accepted\"}",
                Objects.requireNonNull(exchange.getResponse().getBodyAsString().block()));
    }
}
