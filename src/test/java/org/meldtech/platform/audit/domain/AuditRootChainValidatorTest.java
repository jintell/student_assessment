package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;

class AuditRootChainValidatorTest {

    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final Instant SIGNED_AT = Instant.parse("2026-09-03T12:00:00Z");
    private static final CanonicalJsonCodec CODEC = new CanonicalJsonCodec();
    private static final EpochRootDerivation ROOTS = new EpochRootDerivation(CODEC);

    @Test
    void acceptsADenseDuplicateFreeReproducibleRootChain() {
        SignedEpochSeal first = seal(1, zero(), YearMonth.of(2026, 7));
        SignedEpochSeal second =
                seal(2, first.evidence().derivedRoot().epochRoot(), YearMonth.of(2026, 8));
        AuditRootHead head = new AuditRootHead(2, second.evidence().derivedRoot().epochRoot());

        assertThatCode(
                        () ->
                                new AuditRootChainValidator(ROOTS)
                                        .validate(TENANT, List.of(second, first), head))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAGapOrWrongPredecessor() {
        SignedEpochSeal gap = seal(2, zero(), YearMonth.of(2026, 8));

        assertThatThrownBy(
                        () ->
                                new AuditRootChainValidator(ROOTS)
                                        .validate(
                                                TENANT,
                                                List.of(gap),
                                                new AuditRootHead(
                                                        2,
                                                        gap.evidence().derivedRoot().epochRoot())))
                .isInstanceOf(AuditVerificationMismatch.class)
                .hasMessageContaining("dense");
    }

    private static SignedEpochSeal seal(long sequence, AuditHash predecessor, YearMonth period) {
        EpochSealMaterial material =
                new EpochSealMaterial(
                        TENANT,
                        new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, period),
                        1,
                        (short) 1,
                        predecessor,
                        sequence,
                        List.of(ShardSealMaterial.empty(0)));
        DerivedEpochRoot root = ROOTS.derive(material);
        return new SignedEpochSeal(
                new UnsignedEpochSeal(material, root, SIGNED_AT),
                new AuditSignature(
                        "key-v3",
                        "RSASSA_PSS_SHA_256",
                        new byte[] {1},
                        "kms-request-" + sequence,
                        SIGNED_AT));
    }

    private static AuditHash zero() {
        return new AuditHash((short) 1, new byte[32]);
    }
}
