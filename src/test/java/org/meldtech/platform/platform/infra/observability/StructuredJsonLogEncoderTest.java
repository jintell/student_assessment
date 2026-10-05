package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.observability.BusinessEventCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class StructuredJsonLogEncoderTest {

    @Test
    void encodesAnExceptionAsOneJsonLineWithAStructuredStack() {
        StructuredLogEvent event =
                new StructuredLogEvent(
                        Instant.parse("2026-10-03T12:00:00Z"),
                        StructuredLogEvent.Level.ERROR,
                        "example.Logger",
                        "Request \"failed\" safely",
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
                                                        "example.Handler",
                                                        "handle",
                                                        "Handler.java",
                                                        42)),
                                        false)));

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
}
