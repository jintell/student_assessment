package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventPayloadLeakScannerTest {

    @Test
    void acceptsCapturedPayloadWithoutCredentialFields(@TempDir Path directory) throws IOException {
        write(directory, "{\"eventType\":\"people.StudentUpdated.v1\",\"studentId\":\"42\"}");

        assertThatNoException().isThrownBy(() -> EventPayloadLeakScanner.scan(directory));
    }

    @Test
    void plantedPinFailsTheBuild(@TempDir Path directory) throws IOException {
        write(directory, "{\"studentId\":\"42\",\"pin\":\"planted-value\"}");

        assertThatIllegalStateException()
                .isThrownBy(() -> EventPayloadLeakScanner.scan(directory))
                .withMessageContaining("EVENT_PAYLOAD_SECRET_FIELD")
                .withMessageContaining("pin")
                .withMessageNotContaining("planted-value");
    }

    @Test
    void plantedNestedOtpFailsTheBuild(@TempDir Path directory) throws IOException {
        write(directory, "{\"delivery\":{\"oneTimeOtp\":\"planted-value\"}}");

        assertThatIllegalStateException()
                .isThrownBy(() -> EventPayloadLeakScanner.scan(directory))
                .withMessageContaining("EVENT_PAYLOAD_SECRET_FIELD")
                .withMessageContaining("delivery.oneTimeOtp")
                .withMessageNotContaining("planted-value");
    }

    @Test
    void missingCaptureFailsClosed(@TempDir Path directory) {
        assertThatIllegalStateException()
                .isThrownBy(() -> EventPayloadLeakScanner.scan(directory.resolve("missing")))
                .withMessage("EVENT_PAYLOAD_CAPTURE_MISSING");
    }

    private static void write(Path directory, String payload) throws IOException {
        Files.writeString(directory.resolve("captured.json"), payload);
    }
}
