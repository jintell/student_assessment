package org.meldtech.platform.platform.infra.outbox;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ReferenceEventConsumer implements EventConsumer {

    private static final EventType VERSION = EventType.parse("platform.ReferenceEvent.v1");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, Integer> revisions = new ConcurrentHashMap<>();

    @Override
    public Set<EventType> handledVersions() {
        return Set.of(VERSION);
    }

    @Override
    public Mono<ProcessedEventOutcome> handle(ConsumerEnvelope envelope) {
        return Mono.fromCallable(
                () -> {
                    JsonNode payload = objectMapper.readTree(envelope.payload());
                    int revision = payload.path("revision").intValue();
                    int retained = revisions.merge(envelope.aggregateId(), revision, Math::max);
                    return retained == revision
                            ? ProcessedEventOutcome.APPLIED
                            : ProcessedEventOutcome.STALE_VERSION;
                });
    }

    public int revision(String aggregateId) {
        return revisions.getOrDefault(aggregateId, 0);
    }
}
