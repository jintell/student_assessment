package org.meldtech.platform.platform.infra.observability;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;

final class RequestQueryContext {

    private final RequestTelemetry.RequestMetadata metadata;
    private final AtomicLong count = new AtomicLong();

    RequestQueryContext(RequestTelemetry.RequestMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    RequestTelemetry.RequestMetadata metadata() {
        return metadata;
    }

    void increment() {
        count.incrementAndGet();
    }

    long count() {
        return count.get();
    }
}
