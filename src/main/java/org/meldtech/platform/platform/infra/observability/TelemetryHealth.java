package org.meldtech.platform.platform.infra.observability;

interface TelemetryHealth {

    TelemetryHealth NOOP = new TelemetryHealth() {};

    default void exportAttempt(ObservabilityHealthMetrics.Signal signal) {}

    default void exportSuccess(ObservabilityHealthMetrics.Signal signal) {}

    default void exportTimeout(ObservabilityHealthMetrics.Signal signal) {}

    default void dropped(
            ObservabilityHealthMetrics.Signal signal,
            ObservabilityHealthMetrics.DropReason reason,
            int count) {}

    default void queueDepth(ObservabilityHealthMetrics.Signal signal, int depth) {}

    default void redactionRejected(
            ObservabilityHealthMetrics.Surface surface,
            ObservabilityHealthMetrics.RedactionReason reason) {}
}
