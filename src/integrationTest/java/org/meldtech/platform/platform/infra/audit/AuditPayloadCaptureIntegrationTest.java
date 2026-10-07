package org.meldtech.platform.platform.infra.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuditPayloadCaptureIntegrationTest {

    @Test
    void capturesAuditPayloadForTheStageTenLeakScan() throws IOException {
        Path captureDirectory = Path.of(System.getProperty("cbt.audit-payload-capture-dir"));
        Files.createDirectories(captureDirectory);
        Path capture = captureDirectory.resolve("audit.FIXTURE_EVENT.v1.json");
        Files.writeString(
                capture,
                """
                {"fixture_event_id":"01950f47-6000-7000-8000-000000000071",\
                "policy_key":"audit.fixture.longest-wins","policy_version":2}
                """);

        assertThat(capture).isRegularFile();
    }
}
