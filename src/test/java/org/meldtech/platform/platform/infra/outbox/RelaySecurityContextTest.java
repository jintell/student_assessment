package org.meldtech.platform.platform.infra.outbox;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RelaySecurityContextTest {

    @Test
    void onlyClosedOutboxRelaySystemActorIsAccepted() {
        RelaySecurityContext security = new RelaySecurityContext();
        ActorContext relay = actor(SystemActor.OUTBOX_RELAY);

        StepVerifier.create(security.install(relay, Mono.just("allowed")))
                .expectNext("allowed")
                .verifyComplete();

        StepVerifier.create(
                        security.install(actor(SystemActor.GRADING_WORKER), Mono.just("denied")))
                .expectError(SecurityException.class)
                .verify();
    }

    private static ActorContext actor(SystemActor systemActor) {
        return new ActorContext(
                ActorType.SYSTEM,
                new ActorId(systemActor.name()),
                Optional.empty(),
                CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                SourceIp.parse("127.0.0.1"),
                Optional.of(systemActor));
    }
}
