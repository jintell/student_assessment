package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.observability.BusinessEvent;
import org.meldtech.platform.shared.kernel.observability.BusinessEventCode;
import org.meldtech.platform.shared.kernel.observability.PinValidationOutcome;
import reactor.test.StepVerifier;

class MicrometerBusinessEventRecorderTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-03T12:00:00Z");

    @Test
    void recordsEveryBusinessEventThroughTheKernelPort() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerBusinessEventRecorder recorder = new MicrometerBusinessEventRecorder(registry);

        for (BusinessEventCode code : BusinessEventCode.values()) {
            BusinessEvent event =
                    code == BusinessEventCode.PIN_VALIDATION
                            ? BusinessEvent.pinValidation(OCCURRED_AT, PinValidationOutcome.SUCCESS)
                            : BusinessEvent.occurred(code, OCCURRED_AT);
            StepVerifier.create(recorder.record(event)).verifyComplete();
        }

        assertThat(registry.get("exam_started_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("pin_validation_total").tag("outcome", "success").counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.getMeters())
                .extracting(meter -> meter.getId().getName())
                .contains(
                        "exam_started_total",
                        "exam_finished_total",
                        "pin_validation_total",
                        "result_published_total",
                        "correction_applied_total",
                        "provisional_feedback_released_total");
    }
}
