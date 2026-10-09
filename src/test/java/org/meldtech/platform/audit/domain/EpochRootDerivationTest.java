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
        assertThat(first.epochRoot().hex())
                .isEqualTo("901314c825a7f7db5213da39367e138ea740fccaf6d7a8701bf12711fe602862");
        assertThat(first.perShardCounts()).containsExactly(0L, 3L, 0L);
        assertThat(first.sequenceRanges().getFirst().start()).isEmpty();
        assertThat(first.sequenceRanges().getLast().end()).isEmpty();
    }

    @Test
    void fullyPopulatedEpochReproducesItsRootRegardlessOfInputOrder() {
        AuditHash prior = new AuditHash((short) 1, new byte[AuditHash.LENGTH]);
        AuditHash head =
                AuditHashing.recordHash(
                        prior,
                        new CanonicalJsonCodec()
                                .encode(
                                        new org.meldtech.platform.shared.kernel.audit.CanonicalValue
                                                .ObjectValue(java.util.Map.of())));
        ShardSealMaterial first = ShardSealMaterial.populated(0, 3, head);
        ShardSealMaterial second = ShardSealMaterial.populated(1, 3, head);
        ShardSealMaterial third = ShardSealMaterial.populated(2, 3, head);
        EpochRootDerivation derivation = new EpochRootDerivation(new CanonicalJsonCodec());

        DerivedEpochRoot ordered =
                derivation.derive(material(prior, List.of(first, second, third)));
        DerivedEpochRoot shuffled =
                derivation.derive(material(prior, List.of(third, first, second)));

        assertThat(ordered).isEqualTo(shuffled);
        assertThat(ordered.epochRoot().hex())
                .isEqualTo("d8ebf9975cb673a06bf971522fb7f4534bc62d3e1fd129e4d571b6394721f4a8");
        assertThat(ordered.perShardCounts()).containsExactly(3L, 3L, 3L);
        assertThat(ordered.sequenceRanges())
                .allSatisfy(
                        range -> {
                            assertThat(range.start()).hasValue(1);
                            assertThat(range.end()).hasValue(3);
                        });
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
