package org.meldtech.platform.platform.infra.kernel.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.error.ProblemCodeDefinition;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ReactiveTerminationProblemDetailTest {

    private static final Duration VERIFICATION_TIMEOUT = Duration.ofSeconds(1);
    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");

    @Test
    void errorSignalIsMappedWithoutEscaping() {
        MockServerWebExchange exchange = exchange("error");
        ProblemDetailWebExceptionHandler handler = handler();

        Mono<Void> request =
                Mono.<Void>error(new IllegalStateException("error_signal_secret"))
                        .onErrorResume(failure -> handler.handle(exchange, failure));

        StepVerifier.create(request).verifyComplete();
        assertGenericProblem(exchange, "error_signal_secret");
    }

    @Test
    void timeoutSignalIsMappedWithoutEscaping() {
        MockServerWebExchange exchange = exchange("timeout");
        ProblemDetailWebExceptionHandler handler = handler();

        Mono<Void> request =
                Mono.<Void>never()
                        .timeout(Duration.ofMillis(10))
                        .onErrorResume(
                                TimeoutException.class,
                                failure -> handler.handle(exchange, failure));

        StepVerifier.create(request).expectComplete().verify(VERIFICATION_TIMEOUT);
        assertGenericProblem(exchange, "TimeoutException");
    }

    @Test
    void cancellationTerminatesWithoutAResponseLeakOrHang() {
        MockServerWebExchange exchange = exchange("cancellation");
        AtomicBoolean cancelled = new AtomicBoolean();
        Mono<Void> handling =
                handler()
                        .handle(exchange, new IllegalStateException("cancellation_secret"))
                        .delaySubscription(Duration.ofSeconds(5))
                        .doOnCancel(() -> cancelled.set(true));

        StepVerifier.create(handling).thenCancel().verify(VERIFICATION_TIMEOUT);

        assertTrue(cancelled.get());
        assertFalse(exchange.getResponse().isCommitted());
    }

    private static ProblemDetailWebExceptionHandler handler() {
        ProblemCodeDefinition internal =
                new ProblemCodeDefinition(
                        URI.create("https://errors.meld-tech.com/problems/internal"),
                        "Unexpected error",
                        500,
                        "The request could not be completed.",
                        Map.of());
        ProblemDetailMapper mapper =
                new ProblemDetailMapper(
                        Map.of(ProblemDetailMapper.INTERNAL_CODE, internal),
                        Map.of(),
                        ProblemDetailMetrics.NOOP,
                        () -> CORRELATION_ID);
        return new ProblemDetailWebExceptionHandler(mapper, new ObjectMapper());
    }

    private static MockServerWebExchange exchange(String signal) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/fault-injection/reactor/" + signal));
    }

    private static void assertGenericProblem(
            MockServerWebExchange exchange, String forbiddenDetail) {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertEquals(
                CORRELATION_ID.toString(),
                exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
        String body = Objects.requireNonNull(exchange.getResponse().getBodyAsString().block());
        assertTrue(body.contains("\"code\":\"CBT-PLAT-INTERNAL\""));
        assertTrue(body.contains("\"correlationId\":\"" + CORRELATION_ID + "\""));
        assertFalse(body.contains(forbiddenDetail));
    }
}
