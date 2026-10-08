package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultTenantAuditShardProvisioningTest {

    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    void provisionsTheApprovedDefaultBeforeTenantWrites() {
        RecordingRepository repository = new RecordingRepository();
        DefaultTenantAuditShardProvisioning provisioning =
                provisioning(repository, new AtomicReference<>());

        StepVerifier.create(provisioning.provisionDefault(TENANT, YearMonth.of(2026, 10), NOW))
                .assertNext(policy -> assertThat(policy.shardCount()).isEqualTo(64))
                .verifyComplete();

        assertThat(repository.current)
                .hasValueSatisfying(policy -> assertThat(policy.policyVersion()).isEqualTo(1));
    }

    @Test
    void changesOnlyAtAnUnopenedFutureEpochAndRecordsThePolicyFact() {
        RecordingRepository repository = new RecordingRepository();
        repository.current.set(new AuditShardPolicy(TENANT, YearMonth.of(2026, 10), 64, 1));
        AtomicReference<AuditEvent> emitted = new AtomicReference<>();
        DefaultTenantAuditShardProvisioning provisioning = provisioning(repository, emitted);

        StepVerifier.create(
                        provisioning.changeAtEpochBoundary(
                                TENANT, 128, YearMonth.of(2026, 11), 2, actor(), NOW))
                .assertNext(policy -> assertThat(policy.shardCount()).isEqualTo(128))
                .verifyComplete();

        assertThat(java.util.Objects.requireNonNull(emitted.get()).eventType())
                .isEqualTo("audit.AUDIT_SHARD_POLICY_CHANGED.v1");
    }

    @Test
    void refusesAnInPlaceChange() {
        RecordingRepository repository = new RecordingRepository();
        repository.current.set(new AuditShardPolicy(TENANT, YearMonth.of(2026, 10), 64, 1));

        StepVerifier.create(
                        provisioning(repository, new AtomicReference<>())
                                .changeAtEpochBoundary(
                                        TENANT, 128, YearMonth.of(2026, 10), 2, actor(), NOW))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    private static DefaultTenantAuditShardProvisioning provisioning(
            RecordingRepository repository, AtomicReference<AuditEvent> emitted) {
        return new DefaultTenantAuditShardProvisioning(
                repository,
                (event, actor, occurredAt) -> {
                    emitted.set(event);
                    return Mono.empty();
                });
    }

    private static ActorContext actor() {
        return ActorContext.tenantSystem(
                SystemActor.RETENTION_ENGINE,
                TENANT,
                CorrelationId.parse("01K74Q5Y7B0000000000000000"),
                SourceIp.parse("127.0.0.1"));
    }

    private static final class RecordingRepository implements AuditShardPolicyRepository {

        private final AtomicReference<AuditShardPolicy> current = new AtomicReference<>();

        @Override
        public Mono<Void> provision(AuditShardPolicy policy, Instant recordedAt) {
            current.set(policy);
            return Mono.empty();
        }

        @Override
        public Mono<AuditShardPolicy> current(TenantId tenantId) {
            return Mono.just(java.util.Objects.requireNonNull(current.get()));
        }

        @Override
        public Mono<Boolean> appendAtUnopenedEpochBoundary(
                AuditShardPolicy policy, Instant recordedAt) {
            current.set(policy);
            return Mono.just(true);
        }
    }
}
