package org.meldtech.platform.platform.infra.observability;

import java.time.Duration;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;

@FunctionalInterface
public interface DatabaseQueryTelemetry {

    DatabaseQueryTelemetry NOOP = (metadata, duration) -> {};

    void record(RequestTelemetry.RequestMetadata metadata, Duration duration);
}
