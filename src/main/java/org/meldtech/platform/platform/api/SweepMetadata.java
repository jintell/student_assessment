package org.meldtech.platform.platform.api;

import java.time.Duration;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.context.SystemActor;

/** Immutable declaration; registry and deployment validation may impose narrower bounds. */
public record SweepMetadata(
        String name,
        String owningModule,
        String owningFeature,
        SystemActor systemActor,
        Duration defaultInterval,
        Duration minimumInterval,
        Duration maximumInterval,
        Duration maximumBatchDuration,
        Duration maximumTickDuration) {

    public SweepMetadata {
        requireIdentifier(name, "name", "[a-z][a-z0-9]*(?:-[a-z0-9]+)*", 96);
        requireIdentifier(owningModule, "owningModule", "[a-z][a-z0-9]*", 32);
        requireIdentifier(owningFeature, "owningFeature", "FEAT-[A-Z]+-[0-9]{3}", 32);
        Objects.requireNonNull(systemActor, "systemActor");
        requireDuration(defaultInterval, "defaultInterval");
        requireDuration(minimumInterval, "minimumInterval");
        requireDuration(maximumInterval, "maximumInterval");
        requireDuration(maximumBatchDuration, "maximumBatchDuration");
        requireDuration(maximumTickDuration, "maximumTickDuration");
        if (minimumInterval.compareTo(defaultInterval) > 0
                || defaultInterval.compareTo(maximumInterval) > 0) {
            throw new IllegalArgumentException("Sweep default interval must lie within its bounds");
        }
        if (maximumBatchDuration.compareTo(maximumTickDuration) > 0) {
            throw new IllegalArgumentException(
                    "Sweep batch duration must not exceed tick duration");
        }
    }

    private static void requireIdentifier(String value, String field, String pattern, int limit) {
        if (Objects.requireNonNull(value, field).length() > limit || !value.matches(pattern)) {
            throw new IllegalArgumentException("Invalid sweep " + field);
        }
    }

    private static void requireDuration(Duration value, String field) {
        Objects.requireNonNull(value, field);
        try {
            long millis = value.toMillis();
            if (millis <= 0 || !Duration.ofMillis(millis).equals(value)) {
                throw new IllegalArgumentException(
                        "Sweep " + field + " requires positive whole milliseconds");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Sweep " + field + " exceeds supported duration", exception);
        }
    }
}
