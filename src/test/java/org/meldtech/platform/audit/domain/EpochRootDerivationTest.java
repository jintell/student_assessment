package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;

class EpochRootDerivationTest {

    @Test
    void sparseEpochsRetainEmptyShardSentinelsAndZeroCounts() {
        AuditHash prior = new AuditHash((short) 1, new byte[AuditHash.LENGTH]);
        AuditHash populatedHead =
                AuditHashing.recordHash(
                        prior,
                        new CanonicalJsonCodec()
                                .encode(
                                        new org.meldtech.platform.shared.kernel.audit.CanonicalValue
                                                .ObjectValue(java.util.Map.of())));
        EpochSealMaterial ordered =
                material(
                        prior,
                        List.of(
                                ShardSealMaterial.empty(0),
                                ShardSealMaterial.populated(1, 3, populatedHead),
                                ShardSealMaterial.empty(2)));
        EpochSealMaterial shuffled =
                material(
                        prior,
                        List.of(
                                ShardSealMaterial.empty(2),
                                ShardSealMaterial.empty(0),
                                ShardSealMaterial.populated(1, 3, populatedHead)));
        EpochRootDerivation derivation = new EpochRootDerivation(new CanonicalJsonCodec());

        DerivedEpochRoot first = derivation.derive(ordered);
        DerivedEpochRoot second = derivation.derive(shuffled);

        assertThat(first.epochRoot()).isEqualTo(second.epochRoot());
        assertThat(first.perShardCounts()).containsExactly(0L, 3L, 0L);
        assertThat(first.sequenceRanges().getFirst().start()).isEmpty();
    }

    private static EpochSealMaterial material(
            AuditHash previousRoot, List<ShardSealMaterial> shards) {
        return new EpochSealMaterial(
                TenantId.parse("01950f47-6000-7000-8000-000000000001"),
                new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 10)),
                3,
                (short) 1,
                previousRoot,
                1,
                shards);
    }
}
