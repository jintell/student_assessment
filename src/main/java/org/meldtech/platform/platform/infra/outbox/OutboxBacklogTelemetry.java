package org.meldtech.platform.platform.infra.outbox;

interface OutboxBacklogTelemetry {

    void updateBacklog(OutboxBacklogSnapshot snapshot, long collectionStartedNanos);

    void backlogCollectionFailed();
}
