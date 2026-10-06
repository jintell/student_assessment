package org.meldtech.platform.conformance.fixtures.telemetry.domain.invalid;

import io.opentelemetry.api.trace.Tracer;

final class DirectTracer {

    private final Tracer tracer;

    DirectTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    void trace() {
        tracer.spanBuilder("invalid.domainSpan").startSpan().end();
    }
}
