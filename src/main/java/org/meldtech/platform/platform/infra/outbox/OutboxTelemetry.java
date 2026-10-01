package org.meldtech.platform.platform.infra.outbox;

interface OutboxTelemetry {

    void writerAppended();

    void relayPublished();

    void relayPublishFailed(PublicationFailureReason reason);

    void staleClaimsReclaimed(long count);

    void eventFailed();

    void consumerDuplicate();

    void relayTick(java.time.Duration duration);
}
