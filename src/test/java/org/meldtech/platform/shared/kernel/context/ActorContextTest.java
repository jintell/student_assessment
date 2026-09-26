package org.meldtech.platform.shared.kernel.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.identity.TenantId;

class ActorContextTest {

    private static final TenantId TENANT_ID =
            TenantId.parse("018f3f1e-7b2a-7cc5-98c4-2c11e17c4698");
    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");
    private static final SourceIp SOURCE_IP = SourceIp.parse("192.0.2.10");

    @Test
    void createsTenantBoundCandidateAndWorkforceActors() {
        ActorContext candidate =
                ActorContext.candidate(
                        new ActorId("candidate-42"), TENANT_ID, CORRELATION_ID, SOURCE_IP);
        ActorContext workforce =
                ActorContext.tenantWorkforce(
                        new ActorId("staff-7"), TENANT_ID, CORRELATION_ID, SOURCE_IP);

        assertEquals(ActorType.CANDIDATE, candidate.actorType());
        assertEquals(Optional.of(TENANT_ID), candidate.tenantId());
        assertEquals(ActorType.WORKFORCE_USER, workforce.actorType());
    }

    @Test
    void systemActorsComeOnlyFromTheClosedEnumeration() {
        ActorContext actor =
                ActorContext.platformSystem(SystemActor.OUTBOX_RELAY, CORRELATION_ID, SOURCE_IP);

        assertEquals(new ActorId("OUTBOX_RELAY"), actor.actorId());
        assertEquals(Optional.of(SystemActor.OUTBOX_RELAY), actor.systemActorName());
        assertEquals(6, SystemActor.values().length);
        assertThrows(IllegalArgumentException.class, () -> new ActorId("system"));
    }

    @Test
    void invalidActorCombinationsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ActorContext(
                                ActorType.SYSTEM,
                                new ActorId("worker"),
                                Optional.empty(),
                                CORRELATION_ID,
                                SOURCE_IP,
                                Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ActorContext(
                                ActorType.CANDIDATE,
                                new ActorId("candidate-42"),
                                Optional.empty(),
                                CORRELATION_ID,
                                SOURCE_IP,
                                Optional.empty()));
    }

    @Test
    void correlationIdsAreStrictCanonicalUlids() {
        assertTrue(CorrelationId.isValid(CORRELATION_ID.toString()));
        assertThrows(IllegalArgumentException.class, () -> CorrelationId.parse("request-123"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CorrelationId.parse("01j9z9q9j6y7tq4pxkj4d0m3nv"));
    }
}
