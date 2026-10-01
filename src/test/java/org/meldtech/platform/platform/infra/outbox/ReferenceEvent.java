package org.meldtech.platform.platform.infra.outbox;

import org.meldtech.platform.shared.kernel.outbox.IntegrationEvent;

@RegisteredEventContract("platform.ReferenceEvent.v1")
public record ReferenceEvent(String eventType, String referenceId, int revision)
        implements IntegrationEvent {}
