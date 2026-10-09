package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditPayloadPolicy.SecretAuditFieldException;
import org.meldtech.platform.audit.domain.AuditShardCountView;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.RetentionResolver;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.audit.RetentionDecision;
import org.meldtech.platform.shared.kernel.audit.RetentionHorizon;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class R2dbcAuditEmitterTest {

    private static final TenantId TENANT = TenantId.parse("01950f47-6000-7000-8000-000000000001");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void appendsOnTheCallerConnectionWithExplicitAttribution() {
        AtomicReference<PreparedAuditRecord> captured = new AtomicReference<>();
        TransactionalConnection callerConnection =
                sql -> {
                    throw new AssertionError("The fake store owns statement execution");
                };
        AuditAppendStore store = store(captured, Mono.empty());
        R2dbcAuditEmitter emitter = emitter(store);

        StepVerifier.create(
                        Mono.from(emitter.emit(event(), actor(), OCCURRED_AT))
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        TransactionalConnection.class,
                                                        callerConnection)))
                .verifyComplete();

        PreparedAuditRecord record = Objects.requireNonNull(captured.get());
        assertThat(record.sequence()).isEqualTo(9);
        assertThat(record.actor()).isEqualTo(actor());
        assertThat(record.chain().tenantId()).isEqualTo(TENANT);
        assertThat(record.recordHash()).isNotEqualTo(record.previousHash());
        assertThat(record.payloadJson()).isEqualTo("{\"result\":\"requeued\"}");
    }

    @Test
    void appendFailurePropagatesToTheCallerTransaction() {
        IllegalStateException failure = new IllegalStateException("database append failed");
        R2dbcAuditEmitter emitter = emitter(store(new AtomicReference<>(), Mono.error(failure)));

        StepVerifier.create(
                        Mono.from(emitter.emit(event(), actor(), OCCURRED_AT))
                                .contextWrite(
                                        context ->
                                                context.put(
                                                        TransactionalConnection.class,
                                                        (TransactionalConnection)
                                                                sql -> {
                                                                    throw new AssertionError(sql);
                                                                })))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    static Stream<ActorContext> platformActors() {
        ActorContext tenantActor = actor();
        return Stream.of(
                ActorContext.platformWorkforce(
                        tenantActor.actorId(), tenantActor.correlationId(), tenantActor.sourceIp()),
                ActorContext.platformSystem(
                        SystemActor.RETENTION_ENGINE,
                        tenantActor.correlationId(),
                        tenantActor.sourceIp()));
    }

    @ParameterizedTest
    @MethodSource("platformActors")
    void rejectsTenantlessActorsBeforeAccessingTheAuditStore(ActorContext platformActor) {
        AuditAppendStore appendStore = mock(AuditAppendStore.class);

        StepVerifier.create(
                        Mono.from(emitter(appendStore).emit(event(), platformActor, OCCURRED_AT)))
                .expectErrorMatches(
                        failure ->
                                failure instanceof IllegalArgumentException
                                        && "Tenant audit emission requires a tenant actor context"
                                                .equals(failure.getMessage()))
                .verify(Duration.ofSeconds(5));

        verifyNoInteractions(appendStore);
    }

    static Stream<Arguments> secretPayloads() {
        return Stream.of("pin", "otp", "token", "password")
                .flatMap(
                        field -> {
                            StringValue value = new StringValue("synthetic-sensitive-value");
                            ObjectValue direct = new ObjectValue(Map.of(field, value));
                            String message =
                                    new IllegalStateException(field + "=" + value.value())
                                            .getMessage();
                            return Stream.of(
                                    Arguments.of(field, "top-level", direct),
                                    Arguments.of(
                                            field,
                                            "nested",
                                            new ObjectValue(Map.of("details", direct))),
                                    Arguments.of(
                                            field,
                                            "exception-message",
                                            new ObjectValue(
                                                    Map.of(
                                                            "exception_message",
                                                            new StringValue(
                                                                    Objects.requireNonNull(
                                                                            message))))));
                        });
    }

    @ParameterizedTest(name = "production rejects {0} in {1}")
    @MethodSource("secretPayloads")
    void productionProfileRejectsSecretPayloads(
            String secret, String placement, ObjectValue payload) {
        AuditAppendStore appendStore = mock(AuditAppendStore.class);
        AuditEvent attempted =
                new AuditEvent(
                        "platform.OUTBOX_REDRIVE_COMPLETED.v1",
                        event().entity(),
                        event().retentionCandidates(),
                        payload);
        new ApplicationContextRunner()
                .withInitializer(
                        context -> context.getEnvironment().setActiveProfiles("production"))
                .withBean(R2dbcAuditEmitter.class, () -> emitter(appendStore))
                .run(
                        context -> {
                            assertThat(context.getEnvironment().getActiveProfiles())
                                    .containsExactly("production");
                            StepVerifier.create(
                                            Mono.from(
                                                            context.getBean(R2dbcAuditEmitter.class)
                                                                    .emit(
                                                                            attempted,
                                                                            actor(),
                                                                            OCCURRED_AT))
                                                    .contextWrite(
                                                            reactorContext ->
                                                                    reactorContext.put(
                                                                            TransactionalConnection
                                                                                    .class,
                                                                            (TransactionalConnection)
                                                                                    sql -> {
                                                                                        throw new AssertionError(
                                                                                                "Unexpected SQL");
                                                                                    })))
                                    .expectErrorSatisfies(
                                            failure ->
                                                    assertThat(failure)
                                                            .isInstanceOf(
                                                                    SecretAuditFieldException.class)
                                                            .hasMessageNotContaining(
                                                                    "synthetic-sensitive-value")
                                                            .hasNoCause())
                                    .verify(Duration.ofSeconds(5));
                            verifyNoInteractions(appendStore);
                        });
    }

    private static R2dbcAuditEmitter emitter(AuditAppendStore store) {
        RetentionDecision decision =
                new RetentionDecision(
                        "audit.default",
                        3,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Optional.empty(),
                        Map.of(
                                RetentionClass.GENERAL_AUDIT_EVENT,
                                RetentionHorizon.until(Instant.parse("2028-10-08T00:00:00Z"))),
                        RetentionClass.GENERAL_AUDIT_EVENT);
        RetentionResolver resolver =
                new RetentionResolver((time, eventType, entityType, candidates) -> decision);
        AuditShardCountView shardCounts = (tenantId, period) -> 64;
        return new R2dbcAuditEmitter(
                resolver,
                shardCounts,
                () -> UUID.fromString("01950f47-6000-7000-8000-000000000002"),
                new CanonicalJsonCodec(),
                store);
    }

    private static AuditAppendStore store(
            AtomicReference<PreparedAuditRecord> captured, Mono<Void> appendResult) {
        return new AuditAppendStore() {
            @Override
            public Mono<LockedChainHead> lockHead(
                    TransactionalConnection connection, AuditChainKey key) {
                return Mono.just(new LockedChainHead(8, new AuditHash((short) 1, new byte[32])));
            }

            @Override
            public Mono<Void> appendAndAdvance(
                    TransactionalConnection connection,
                    LockedChainHead lockedHead,
                    PreparedAuditRecord record) {
                captured.set(record);
                return appendResult;
            }
        };
    }

    private static AuditEvent event() {
        return new AuditEvent(
                "platform.OUTBOX_REDRIVE_COMPLETED.v1",
                new EntityRef("outbox.event", "event-1"),
                Set.of(RetentionClass.GENERAL_AUDIT_EVENT),
                new ObjectValue(Map.of("result", new StringValue("requeued"))));
    }

    private static ActorContext actor() {
        return ActorContext.tenantWorkforce(
                new ActorId("operator-1"),
                TENANT,
                CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                SourceIp.parse("127.0.0.1"));
    }
}
