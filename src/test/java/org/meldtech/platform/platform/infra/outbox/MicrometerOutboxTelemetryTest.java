package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MicrometerOutboxTelemetryTest {

    @Test
    void exposesBoundedRelayWriterAndConsumerMetricsWithoutTenantLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerOutboxTelemetry telemetry = new MicrometerOutboxTelemetry(registry);

        telemetry.writerAppended();
        telemetry.relayPublished();
        telemetry.relayPublishFailed(PublicationFailureReason.BROKER_NACK);
        telemetry.staleClaimsReclaimed(2);
        telemetry.eventFailed();
        telemetry.consumerDuplicate();
        telemetry.relayTick(Duration.ofMillis(25));
        telemetry.updateBacklog(
                Map.of(MicrometerOutboxTelemetry.OutboxState.PENDING, 7L), Duration.ofSeconds(12));

        assertThat(registry.get("outbox.writer.appended").counter().count()).isEqualTo(1);
        assertThat(registry.get("outbox.relay.published").counter().count()).isEqualTo(1);
        assertThat(registry.get("outbox.backlog.depth").tag("state", "PENDING").gauge().value())
                .isEqualTo(7);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(12);
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .allSatisfy(id -> assertThat(id.getTag("tenant_id")).isNull());
    }
}
