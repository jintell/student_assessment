package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.DerivedEpochRoot;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochRootDerivation;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.ShardSealMaterial;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.audit.domain.UnsignedEpochSeal;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditDispositionEvidenceEmitterTest {

    private static final Instant SIGNED_AT = Instant.parse("2026-09-03T12:00:00Z");
    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");

    @Test
    void emitsCompleteDisposalEvidenceIntoTheCurrentEpoch() {
        AtomicReference<AuditEvent> emitted = new AtomicReference<>();
        AtomicReference<Instant> occurredAt = new AtomicReference<>();
        AuditDispositionEvidenceEmitter evidenceEmitter =
                new AuditDispositionEvidenceEmitter(
                        (event, actor, occurrence) -> {
                            emitted.set(event);
                            occurredAt.set(occurrence);
                            return Mono.empty();
                        });
        Instant currentEpochTime = Instant.parse("2026-10-08T12:00:00Z");

        StepVerifier.create(
                        evidenceEmitter.emit(
                                request(),
                                seal(),
                                ActorContext.tenantSystem(
                                        SystemActor.RETENTION_ENGINE,
                                        TENANT,
                                        CorrelationId.parse("01K74Q5Y7B0000000000000000"),
                                        SourceIp.parse("127.0.0.1")),
                                currentEpochTime))
                .verifyComplete();

        AuditEvent event = java.util.Objects.requireNonNull(emitted.get());
        assertThat(event.eventType()).isEqualTo("audit.AUDIT_EPOCH_DISPOSED.v1");
        assertThat(occurredAt).hasValue(currentEpochTime);
        assertThat(
                        ((StringValue)
                                        java.util.Objects.requireNonNull(
                                                event.payload().members().get("disposed_period")))
                                .value())
                .isEqualTo("2026-08");
        assertThat(
                        ((StringValue)
                                        java.util.Objects.requireNonNull(
                                                event.payload().members().get("policy_key")))
                                .value())
                .isEqualTo("audit.general.v3");
        assertThat(
                        ((IntegerValue)
                                        java.util.Objects.requireNonNull(
                                                event.payload().members().get("policy_version")))
                                .value())
                .isEqualTo(java.math.BigInteger.valueOf(3));
        assertThat(event.payload().members())
                .containsKeys(
                        "sequence_ranges",
                        "root_hash",
                        "signature_reference",
                        "request_id",
                        "authorization_reference");
    }

    private static DispositionRequest request() {
        return new DispositionRequest(
                UUID.fromString("01991a95-df27-7000-8000-000000000099"),
                TENANT,
                epoch(),
                "audit.general.v3",
                3,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                "approval-42");
    }

    private static SignedEpochSeal seal() {
        EpochSealMaterial material =
                new EpochSealMaterial(
                        TENANT,
                        epoch(),
                        1,
                        (short) 1,
                        new AuditHash((short) 1, new byte[32]),
                        4,
                        List.of(
                                ShardSealMaterial.populated(
                                        0, 2, new AuditHash((short) 1, hashBytes()))));
        DerivedEpochRoot root = new EpochRootDerivation(new CanonicalJsonCodec()).derive(material);
        return new SignedEpochSeal(
                new UnsignedEpochSeal(material, root, SIGNED_AT),
                new AuditSignature(
                        "key-v3",
                        "RSASSA_PSS_SHA_256",
                        new byte[] {1},
                        "kms-request-42",
                        SIGNED_AT));
    }

    private static EpochIdentity epoch() {
        return new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 8));
    }

    private static byte[] hashBytes() {
        byte[] bytes = new byte[32];
        bytes[0] = 1;
        return bytes;
    }
}
