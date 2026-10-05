package org.meldtech.platform.platform.infra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.observability.BusinessEvent;
import org.meldtech.platform.shared.kernel.observability.BusinessEventCode;
import org.meldtech.platform.shared.kernel.observability.BusinessEventRecorder;
import org.meldtech.platform.shared.kernel.observability.PinValidationOutcome;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
final class MicrometerBusinessEventRecorder implements BusinessEventRecorder {

    private final Map<BusinessEventCode, Counter> counters;
    private final Map<PinValidationOutcome, Counter> pinValidationCounters;

    MicrometerBusinessEventRecorder(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        EnumMap<BusinessEventCode, Counter> events = new EnumMap<>(BusinessEventCode.class);
        events.put(BusinessEventCode.EXAM_STARTED, registry.counter("exam_started_total"));
        events.put(BusinessEventCode.EXAM_FINISHED, registry.counter("exam_finished_total"));
        events.put(BusinessEventCode.RESULT_PUBLISHED, registry.counter("result_published_total"));
        events.put(
                BusinessEventCode.CORRECTION_APPLIED, registry.counter("correction_applied_total"));
        events.put(
                BusinessEventCode.PROVISIONAL_FEEDBACK_RELEASED,
                registry.counter("provisional_feedback_released_total"));
        counters = Map.copyOf(events);

        EnumMap<PinValidationOutcome, Counter> pinCounters =
                new EnumMap<>(PinValidationOutcome.class);
        for (PinValidationOutcome outcome : PinValidationOutcome.values()) {
            pinCounters.put(
                    outcome,
                    Counter.builder("pin_validation_total")
                            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                            .register(registry));
        }
        pinValidationCounters = Map.copyOf(pinCounters);
    }

    @Override
    public Mono<Void> record(BusinessEvent event) {
        Objects.requireNonNull(event, "event");
        return Mono.fromRunnable(() -> increment(event)).onErrorComplete().then();
    }

    private void increment(BusinessEvent event) {
        if (event.eventCode() == BusinessEventCode.PIN_VALIDATION) {
            PinValidationOutcome outcome = event.pinValidationOutcome().orElseThrow();
            Objects.requireNonNull(
                            pinValidationCounters.get(outcome),
                            "PIN validation metric must be registered")
                    .increment();
            return;
        }
        Counter counter = counters.get(event.eventCode());
        if (counter == null) {
            throw new IllegalStateException(
                    "No metric is registered for business event " + event.eventCode());
        }
        counter.increment();
    }
}
