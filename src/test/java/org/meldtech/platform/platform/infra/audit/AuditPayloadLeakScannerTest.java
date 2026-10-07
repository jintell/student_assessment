package org.meldtech.platform.platform.infra.audit;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditPayloadLeakScannerTest {

    @Test
    void acceptsRequiredPolicyKey(@TempDir Path directory) throws IOException {
        write(directory, "{\"policy_key\":\"retention.audit.v2\",\"policy_version\":2}");

        assertThatNoException().isThrownBy(() -> AuditPayloadLeakScanner.scan(directory));
    }

    @Test
    void plantedNestedPinFailsTheBuildWithoutEchoingItsValue(@TempDir Path directory)
            throws IOException {
        write(directory, "{\"candidate\":{\"pin\":\"planted-value\"}}");

        assertThatIllegalStateException()
                .isThrownBy(() -> AuditPayloadLeakScanner.scan(directory))
                .withMessageContaining("AUDIT_PAYLOAD_SECRET_FIELD")
                .withMessageContaining("candidate.pin")
                .withMessageNotContaining("planted-value");
    }

    @Test
    void missingCaptureFailsClosed(@TempDir Path directory) {
        assertThatIllegalStateException()
                .isThrownBy(() -> AuditPayloadLeakScanner.scan(directory.resolve("missing")))
                .withMessage("AUDIT_PAYLOAD_CAPTURE_MISSING");
    }

    private static void write(Path directory, String payload) throws IOException {
        Files.writeString(directory.resolve("captured.json"), payload);
    }
}
