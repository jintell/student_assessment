package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.observability.BusinessEventCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class StructuredJsonLogEncoderTest {

    private static final Set<String> ALWAYS_PRESENT_FIELDS =
            Set.of(
                    "timestamp",
                    "level",
                    "logger",
                    "message",
                    "correlationId",
                    "traceId",
                    "spanId",
                    "role",
                    "module",
                    "slice");
    private static final Set<String> DECLARED_FIELDS =
            Set.of(
                    "timestamp",
                    "level",
                    "logger",
                    "message",
                    "correlationId",
                    "traceId",
                    "spanId",
                    "role",
                    "module",
                    "slice",
                    "actorType",
                    "actorId",
                    "tenantId",
                    "eventCode",
                    "errorCode",
                    "durationMs",
                    "dbQueryCount",
                    "error");

    @Test
    void encodesAnExceptionAsOneJsonLineWithAStructuredStack() {
        StructuredLogEvent event = structuredEvent();

        String encoded =
                new String(new StructuredJsonLogEncoder().encode(event), StandardCharsets.UTF_8);

        assertThat(encoded).endsWith("\n");
        assertThat(encoded.chars().filter(character -> character == '\n')).hasSize(1);
        JsonNode document = new ObjectMapper().readTree(encoded);
        assertThat(document.path("message").stringValue()).isEqualTo("Request \"failed\" safely");
        assertThat(document.path("actorType").stringValue()).isEqualTo("WORKFORCE_USER");
        assertThat(document.path("actorId").stringValue()).isEqualTo("operator-123");
        assertThat(document.path("tenantId").stringValue())
                .isEqualTo("01950f47-6000-7000-8000-000000000001");
        assertThat(document.path("eventCode").stringValue()).isEqualTo("EXAM_STARTED");
        assertThat(document.path("error").path("stack")).hasSize(1);
        assertThat(document.path("error").path("stack").get(0).path("method").stringValue())
                .isEqualTo("handle");
        assertThat(document.toString()).doesNotContain("exceptionMessage");
    }

    @Test
    void emitsEveryAlwaysPresentFieldAndNoUndeclaredField() {
        StructuredJsonLogEncoder encoder = new StructuredJsonLogEncoder();
        ObjectMapper mapper = new ObjectMapper();

        JsonNode minimal =
                mapper.readTree(new String(encoder.encode(minimalEvent()), StandardCharsets.UTF_8));
        JsonNode complete =
                mapper.readTree(
                        new String(encoder.encode(structuredEvent()), StandardCharsets.UTF_8));

        assertThat(minimal.propertyNames())
                .containsExactlyInAnyOrderElementsOf(ALWAYS_PRESENT_FIELDS);
        assertThat(complete.propertyNames()).containsExactlyInAnyOrderElementsOf(DECLARED_FIELDS);
    }

    @Test
    void serializationFailureDoesNotExposeItsAnswerContentMessage() {
        String prohibitedDetail = "submitted-answer-must-not-escape";
        ObjectMapper failingMapper =
                new ObjectMapper() {
                    @Override
                    public String writeValueAsString(Object value) {
                        throw new IllegalStateException(prohibitedDetail);
                    }
                };
        StructuredJsonLogEncoder encoder =
                new StructuredJsonLogEncoder(failingMapper, new RedactingJsonSerializer());

        assertThatThrownBy(() -> encoder.encode(structuredEvent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Structured log event could not be encoded")
                .hasMessageNotContaining(prohibitedDetail)
                .hasNoCause();
    }

    @Test
    void hostileValuesCannotForgeOrBreakEventsAtTheSink(@TempDir Path directory)
            throws IOException {
        Path sink = directory.resolve("operational.jsonl");
        StructuredJsonLogEncoder encoder = new StructuredJsonLogEncoder();

        assertThatThrownBy(() -> structuredEvent("first event\nforged event"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single-line");
        assertThat(sink).doesNotExist();

        List<String> hostileValues =
                List.of(
                        "control:" + (char) 1,
                        "ansi:" + (char) 27 + "[31m",
                        "json:{\"level\":\"ERROR\"}",
                        "long:" + "x".repeat(65_536));
        for (String hostileValue : hostileValues) {
            Files.write(
                    sink,
                    encoder.encode(structuredEvent(hostileValue)),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(sink, StandardCharsets.UTF_8)) {
            events.add(new ObjectMapper().readTree(line));
        }
        assertThat(events).hasSize(hostileValues.size());
        assertThat(events.stream().map(event -> event.path("message").stringValue()).toList())
                .containsExactlyElementsOf(hostileValues);

        String sinkOutput = Files.readString(sink, StandardCharsets.UTF_8);
        assertThat(sinkOutput.chars().filter(character -> character == '\n'))
                .hasSize(hostileValues.size());
        assertThat(sinkOutput).doesNotContain(String.valueOf((char) 1), String.valueOf((char) 27));
    }

    private static StructuredLogEvent structuredEvent() {
        return structuredEvent("Request \"failed\" safely");
    }

    private static StructuredLogEvent minimalEvent() {
        return new StructuredLogEvent(
                Instant.parse("2026-10-03T12:00:00Z"),
                StructuredLogEvent.Level.INFO,
                "example.Logger",
                "Request received",
                CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"),
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                StructuredLogEvent.RuntimeRole.API,
                "platform",
                "getConformanceReference",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static StructuredLogEvent structuredEvent(String message) {
        return new StructuredLogEvent(
                Instant.parse("2026-10-03T12:00:00Z"),
                StructuredLogEvent.Level.ERROR,
                "example.Logger",
                message,
                CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"),
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                StructuredLogEvent.RuntimeRole.API,
                "platform",
                "getConformanceReference",
                Optional.of(ActorType.WORKFORCE_USER),
                Optional.of(new ActorId("operator-123")),
                Optional.of(TenantId.parse("01950f47-6000-7000-8000-000000000001")),
                Optional.of(BusinessEventCode.EXAM_STARTED),
                Optional.of("PLATFORM_FAILURE"),
                Optional.of(12L),
                Optional.of(2L),
                Optional.of(
                        new StructuredLogEvent.StructuredError(
                                List.of(
                                        new StructuredLogEvent.StackFrame(
                                                "example.Handler", "handle", "Handler.java", 42)),
                                false)));
    }
}
