package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

class OperationalLogCaptureTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationalLogCaptureTest.class);
    private static final String CORRELATION_ID = "01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV";

    @Test
    void capturesAJsonOperationalEventForTheBlockingScanner() throws IOException {
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(OperationalLogCaptureTest.class)) {
            MDC.put("correlationId", CORRELATION_ID);
            try {
                LOGGER.info("Operational log capture probe completed");
            } finally {
                MDC.remove("correlationId");
            }

            ILoggingEvent event = telemetry.logEvents().getFirst();
            Path captureDirectory =
                    Path.of(
                            System.getProperty(
                                    "cbt.operational-log-capture-dir",
                                    "build/reports/operational-logs"));
            Files.createDirectories(captureDirectory);
            Path capture = captureDirectory.resolve("reference.jsonl");
            Files.writeString(
                    capture,
                    new ObjectMapper().writeValueAsString(toStructuredEvent(event))
                            + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            assertThat(capture).isNotEmptyFile();
        }
    }

    private static Map<String, Object> toStructuredEvent(ILoggingEvent event) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        structured.put("level", event.getLevel().toString());
        structured.put("logger", event.getLoggerName());
        structured.put("message", event.getFormattedMessage());
        structured.put("correlationId", event.getMDCPropertyMap().get("correlationId"));
        structured.put("traceId", "11111111111111111111111111111111");
        structured.put("spanId", "2222222222222222");
        structured.put("role", "api");
        structured.put("module", "platform");
        structured.put("slice", "getConformanceReference");
        return Map.copyOf(structured);
    }
}
