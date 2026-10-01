package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EventPayloadCaptureIntegrationTest {

    @Test
    void capturesSchemaValidatedPayloadForTheStageTenLeakScan() throws IOException {
        String payload =
                new RegisteredEventSchemaValidator(Path.of("contracts/events"), new ObjectMapper())
                        .validateAndSerialize(
                                EventType.parse("platform.ReferenceEvent.v1"),
                                new ReferenceEvent(
                                        "platform.ReferenceEvent.v1",
                                        "01950f47-6000-7000-8000-000000000003",
                                        1));
        Path captureDirectory = Path.of(System.getProperty("cbt.event-payload-capture-dir"));
        Files.createDirectories(captureDirectory);
        Path capture = captureDirectory.resolve("platform.ReferenceEvent.v1.json");
        Files.writeString(capture, payload);

        assertThat(capture).isRegularFile();
    }
}
