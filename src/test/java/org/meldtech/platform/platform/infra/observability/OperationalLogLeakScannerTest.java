package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationalLogLeakScannerTest {

    @Test
    void acceptsSafeStructuredEvents(@TempDir Path directory) throws IOException {
        Path captures = capture(directory, "{" + "\"message\":\"request completed\"}");
        Path markers = markers(directory, "OBS_PIN_LEAK_SENTINEL_7ZK4");

        assertThatNoException().isThrownBy(() -> OperationalLogLeakScanner.scan(captures, markers));
    }

    @Test
    void rejectsSecretNamedFields(@TempDir Path directory) throws IOException {
        Path captures = capture(directory, "{" + "\"authorizationToken\":\"redacted\"}");
        Path markers = markers(directory, "OBS_PIN_LEAK_SENTINEL_7ZK4");

        assertThatThrownBy(() -> OperationalLogLeakScanner.scan(captures, markers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPERATIONAL_LOG_SECRET_FIELD");
    }

    @Test
    void rejectsForbiddenValuesWithoutEchoingThem(@TempDir Path directory) throws IOException {
        String marker = "OBS_PIN_LEAK_SENTINEL_7ZK4";
        Path captures = capture(directory, "{" + "\"message\":\"" + marker + "\"}");
        Path markers = markers(directory, marker);

        assertThatThrownBy(() -> OperationalLogLeakScanner.scan(captures, markers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPERATIONAL_LOG_SECRET_VALUE")
                .hasMessageNotContaining(marker);
    }

    @Test
    void rejectsAnEmptyCaptureSet(@TempDir Path directory) throws IOException {
        Path captures = Files.createDirectory(directory.resolve("empty"));
        Path markers = markers(directory, "OBS_PIN_LEAK_SENTINEL_7ZK4");

        assertThatThrownBy(() -> OperationalLogLeakScanner.scan(captures, markers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OPERATIONAL_LOG_CAPTURE_EMPTY");
    }

    private static Path capture(Path directory, String event) throws IOException {
        Path captures = Files.createDirectory(directory.resolve("captures"));
        Files.writeString(captures.resolve("events.jsonl"), event + System.lineSeparator());
        return captures;
    }

    private static Path markers(Path directory, String marker) throws IOException {
        Path markers = directory.resolve("markers.txt");
        Files.writeString(markers, marker + System.lineSeparator());
        return markers;
    }
}
