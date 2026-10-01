package org.meldtech.platform.conformance.fixtures.answeracceptance;

import org.meldtech.platform.shared.kernel.outbox.OutboxWriter;

public final class Handler {

    private final OutboxWriter outboxWriter;

    public Handler(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public OutboxWriter forbiddenDependency() {
        return outboxWriter;
    }
}
