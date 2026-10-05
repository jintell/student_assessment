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
        for (BusinessEventCode code : BusinessEventCode.values()) {
            if (code != BusinessEventCode.PIN_VALIDATION) {
                events.put(code, registry.counter(metricName(code)));
            }
        }
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

    static Map<BusinessEventCode, String> registeredMetricNames() {
        EnumMap<BusinessEventCode, String> registrations = new EnumMap<>(BusinessEventCode.class);
        for (BusinessEventCode code : BusinessEventCode.values()) {
            registrations.put(code, metricName(code));
        }
        return Map.copyOf(registrations);
    }

    private static String metricName(BusinessEventCode code) {
        return switch (Objects.requireNonNull(code, "code")) {
            case EXAM_STARTED -> "exam_started_total";
            case EXAM_FINISHED -> "exam_finished_total";
            case PIN_VALIDATION -> "pin_validation_total";
            case RESULT_PUBLISHED -> "result_published_total";
            case CORRECTION_APPLIED -> "correction_applied_total";
            case PROVISIONAL_FEEDBACK_RELEASED -> "provisional_feedback_released_total";
        };
    }
}
