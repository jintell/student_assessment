package org.meldtech.platform.platform.infra.kernel.error;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.error.ProblemCodeDefinition;
import org.meldtech.platform.shared.kernel.error.ProblemDetailDocument;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class ProblemDetailFailureToleranceTest {

    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");

    @Test
    void catalogueMissReturnsGenericProblemAndRecordsOneFallback() {
        RecordingMetrics metrics = new RecordingMetrics();
        ProblemDetailMapper mapper =
                new ProblemDetailMapper(
                        catalogue(),
                        Map.of(IllegalArgumentException.class, "CBT-PLAT-MISSING"),
                        metrics,
                        () -> CORRELATION_ID);
        AtomicReference<ProblemDetailDocument> response = new AtomicReference<>();

        assertDoesNotThrow(
                () ->
                        response.set(
                                mapper.mapSafely(
                                        new IllegalArgumentException("provider_secret"),
                                        URI.create("/failure"),
                                        Optional.of(CORRELATION_ID.toString()))));

        assertGeneric(Objects.requireNonNull(response.get()));
        assertEquals(1, metrics.count(ProblemDetailMetrics.FallbackReason.CATALOGUE_MISS));
    }

    @Test
    void missingCorrelationReturnsGenericProblemAndRecordsOneFallback() {
        RecordingMetrics metrics = new RecordingMetrics();
        ProblemDetailMapper mapper =
                new ProblemDetailMapper(catalogue(), Map.of(), metrics, () -> CORRELATION_ID);
        AtomicReference<ProblemDetailDocument> response = new AtomicReference<>();

        assertDoesNotThrow(
                () ->
                        response.set(
                                mapper.mapSafely(
                                        new IllegalStateException("provider_secret"),
                                        URI.create("/failure"),
                                        Optional.empty())));

        assertGeneric(Objects.requireNonNull(response.get()));
        assertEquals(1, metrics.count(ProblemDetailMetrics.FallbackReason.MISSING_CORRELATION));
    }

    @Test
    void serializationFailureReturnsGenericProblemAndRecordsOneFallback() {
        RecordingMetrics metrics = new RecordingMetrics();
        ProblemDetailMapper mapper =
                new ProblemDetailMapper(catalogue(), Map.of(), metrics, () -> CORRELATION_ID);
        ProblemDetailWebExceptionHandler handler =
                new ProblemDetailWebExceptionHandler(mapper, new FailingObjectMapper());
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/failure"));

        assertDoesNotThrow(
                () -> {
                    handler.handle(exchange, new IllegalStateException("provider_secret")).block();
                });

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        String body = Objects.requireNonNull(exchange.getResponse().getBodyAsString().block());
        assertTrue(body.contains("\"code\":\"CBT-PLAT-INTERNAL\""));
        assertTrue(body.contains("\"detail\":\"The request could not be completed.\""));
        assertTrue(body.contains("\"correlationId\":\"" + CORRELATION_ID + "\""));
        assertFalse(body.contains("provider_secret"));
        assertEquals(1, metrics.count(ProblemDetailMetrics.FallbackReason.SERIALIZATION_FAILURE));
    }

    private static void assertGeneric(ProblemDetailDocument problem) {
        assertEquals(ProblemDetailMapper.INTERNAL_CODE, problem.code());
        assertEquals(500, problem.status());
        assertEquals("Unexpected error", problem.title());
        assertEquals("The request could not be completed.", problem.detail());
        assertEquals(CORRELATION_ID, problem.correlationId());
        assertTrue(problem.extensions().isEmpty());
    }

    private static Map<String, ProblemCodeDefinition> catalogue() {
        return Map.of(
                ProblemDetailMapper.INTERNAL_CODE,
                new ProblemCodeDefinition(
                        URI.create("https://errors.meld-tech.com/problems/internal"),
                        "Unexpected error",
                        500,
                        "The request could not be completed.",
                        Map.of()));
    }

    private static final class RecordingMetrics implements ProblemDetailMetrics {

        private final Map<FallbackReason, Integer> fallbackCounts =
                new EnumMap<>(FallbackReason.class);

        @Override
        public void emitted(String code) {}

        @Override
        public void fallback(FallbackReason reason) {
            fallbackCounts.merge(reason, 1, Integer::sum);
        }

        private int count(FallbackReason reason) {
            return fallbackCounts.getOrDefault(reason, 0);
        }
    }

    private static final class FailingObjectMapper extends ObjectMapper {

        private static final long serialVersionUID = 1L;

        @Override
        public byte[] writeValueAsBytes(Object value) throws JacksonException {
            throw new TestJacksonException("forced serialization failure");
        }
    }

    private static final class TestJacksonException extends JacksonException {

        private static final long serialVersionUID = 1L;

        private TestJacksonException(String message) {
            super(message);
        }
    }
}
