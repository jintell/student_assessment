package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

class AuditChainWalkTest {
    private static final AuditHash SEED = new AuditHash((short) 1, new byte[32]);
    private static final AuditChainRecord FIRST = record(1, SEED, "first");
    private static final AuditChainRecord SECOND = record(2, FIRST.recordHash(), "second");

    @Test
    void intactChainMatchesItsCommittedHead() {
        AuditChainWalk.State state = walk(List.of(FIRST, SECOND));
        AuditChainWalk.finish(state, 2, SECOND.recordHash());
        assertThat(state.nextSequence()).isEqualTo(3);
    }

    @Test
    void tamperedPayloadFailsVerification() {
        AuditChainRecord tampered =
                new AuditChainRecord(1, SEED, FIRST.recordHash(), payload("changed"));
        assertThatThrownBy(() -> walk(List.of(tampered, SECOND)))
                .isInstanceOf(AuditVerificationMismatch.class)
                .hasMessage("audit record hash does not reproduce");
    }

    @Test
    void reorderedRecordsFailVerification() {
        assertThatThrownBy(() -> walk(List.of(SECOND, FIRST)))
                .isInstanceOf(AuditVerificationMismatch.class)
                .hasMessage("audit chain sequence is not dense");
    }

    @Test
    void substitutedPreviousHashFailsVerification() {
        AuditChainRecord substituted =
                new AuditChainRecord(2, SEED, SECOND.recordHash(), SECOND.canonicalEvent());
        assertThatThrownBy(() -> walk(List.of(FIRST, substituted)))
                .isInstanceOf(AuditVerificationMismatch.class)
                .hasMessage("audit chain predecessor does not match");
    }

    private static AuditChainWalk.State walk(List<AuditChainRecord> records) {
        AuditChainWalk.State state = AuditChainWalk.begin(SEED);
        for (AuditChainRecord record : records) {
            state = AuditChainWalk.append(state, record);
        }
        return state;
    }

    private static AuditChainRecord record(long sequence, AuditHash previous, String value) {
        CanonicalDocument payload = payload(value);
        return new AuditChainRecord(
                sequence, previous, AuditHashing.recordHash(previous, payload), payload);
    }

    private static CanonicalDocument payload(String value) {
        return new CanonicalJsonCodec()
                .encode(new ObjectValue(Map.of("event", new StringValue(value))));
    }
}
