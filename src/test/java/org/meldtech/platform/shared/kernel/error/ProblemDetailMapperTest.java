package org.meldtech.platform.shared.kernel.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.CorrelationId;

class ProblemDetailMapperTest {

    private static final ProblemContext CONTEXT =
            new ProblemContext(
                    URI.create("/api/v1/assessments"),
                    CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"));

    @Test
    void mappedFailureUsesOnlyFixedCatalogueLanguage() {
        ProblemDetailMapper mapper = mapper();
        IllegalArgumentException failure =
                new IllegalArgumentException("password=secret SELECT * FROM users");

        ProblemDetailDocument problem = mapper.map(failure, CONTEXT);

        assertEquals("CBT-PLAT-VALIDATION", problem.code());
        assertEquals("One or more request values are invalid.", problem.detail());
        assertFalse(problem.detail().contains(failure.getMessage()));
    }

    @Test
    void unmappedFailureUsesTheGenericNonDisclosingEntry() {
        ProblemDetailDocument problem =
                mapper().map(new RuntimeException("provider secret"), CONTEXT);

        assertEquals(ProblemDetailMapper.INTERNAL_CODE, problem.code());
        assertEquals("The request could not be completed.", problem.detail());
    }

    @Test
    void catalogueMissFallsBackAndRecordsTheReason() {
        RecordingMetrics metrics = new RecordingMetrics();
        ProblemDetailMapper mapper =
                new ProblemDetailMapper(
                        Map.of(
                                ProblemDetailMapper.INTERNAL_CODE,
                                definition(
                                        "internal",
                                        "Unexpected error",
                                        500,
                                        "The request could not be completed.")),
                        Map.of(IllegalArgumentException.class, "CBT-PLAT-MISSING"),
                        metrics);

        ProblemDetailDocument problem = mapper.map(new IllegalArgumentException("hidden"), CONTEXT);

        assertEquals(ProblemDetailMapper.INTERNAL_CODE, problem.code());
        assertEquals(
                Optional.of(ProblemDetailMetrics.FallbackReason.CATALOGUE_MISS),
                metrics.lastFallback);
    }

    @Test
    void missingCorrelationGeneratesAValidReplacement() {
        RecordingMetrics metrics = new RecordingMetrics();
        CorrelationId generated = CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NW");
        ProblemDetailMapper mapper =
                new ProblemDetailMapper(
                        Map.of(
                                ProblemDetailMapper.INTERNAL_CODE,
                                definition(
                                        "internal",
                                        "Unexpected error",
                                        500,
                                        "The request could not be completed.")),
                        Map.of(),
                        metrics,
                        () -> generated);

        ProblemDetailDocument problem =
                mapper.mapSafely(
                        new RuntimeException("hidden"), URI.create("/failed"), Optional.empty());

        assertEquals(generated, problem.correlationId());
        assertEquals(
                Optional.of(ProblemDetailMetrics.FallbackReason.MISSING_CORRELATION),
                metrics.lastFallback);
    }

    @Test
    void serializationFallbackIsMinimalGenericJson() {
        RecordingMetrics metrics = new RecordingMetrics();
        ProblemDetailMapper mapper =
                new ProblemDetailMapper(Map.of(), Map.of(), metrics, () -> CONTEXT.correlationId());

        String json =
                new String(
                        mapper.renderMinimalFallback(CONTEXT.instance(), CONTEXT.correlationId()),
                        java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(7, json.split("\":").length - 1);
        assertFalse(json.contains("exception"));
        assertEquals(
                Optional.of(ProblemDetailMetrics.FallbackReason.SERIALIZATION_FAILURE),
                metrics.lastFallback);
    }

    @Test
    void rejectsSecretShapedCatalogueAndSuppliedExtensions() {
        ProblemCodeDefinition unsafe =
                new ProblemCodeDefinition(
                        URI.create("https://errors.meld-tech.com/problems/unsafe"),
                        "Unsafe",
                        400,
                        "Unsafe.",
                        Map.of(
                                "accessToken",
                                new ProblemCodeDefinition.ExtensionRule(
                                        ProblemCodeDefinition.PrimitiveType.STRING, false)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProblemDetailMapper(
                                Map.of(
                                        ProblemDetailMapper.INTERNAL_CODE,
                                        definition(
                                                "internal",
                                                "Unexpected error",
                                                500,
                                                "The request could not be completed."),
                                        "CBT-PLAT-UNSAFE",
                                        unsafe),
                                Map.of(),
                                ProblemDetailMetrics.NOOP));
    }

    private static ProblemDetailMapper mapper() {
        Map<String, ProblemCodeDefinition> catalogue = new LinkedHashMap<>();
        catalogue.put(
                ProblemDetailMapper.INTERNAL_CODE,
                definition(
                        "internal",
                        "Unexpected error",
                        500,
                        "The request could not be completed."));
        catalogue.put(
                "CBT-PLAT-VALIDATION",
                definition(
                        "validation",
                        "Validation failed",
                        400,
                        "One or more request values are invalid."));
        return new ProblemDetailMapper(
                catalogue,
                Map.of(IllegalArgumentException.class, "CBT-PLAT-VALIDATION"),
                ProblemDetailMetrics.NOOP);
    }

    private static ProblemCodeDefinition definition(
            String path, String title, int status, String detail) {
        return new ProblemCodeDefinition(
                URI.create("https://errors.meld-tech.com/problems/" + path),
                title,
                status,
                detail,
                Map.of());
    }

    private static final class RecordingMetrics implements ProblemDetailMetrics {

        private Optional<FallbackReason> lastFallback = Optional.empty();

        @Override
        public void emitted(String code) {}

        @Override
        public void fallback(FallbackReason reason) {
            lastFallback = Optional.of(reason);
        }
    }
}
