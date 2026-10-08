package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;

class AuditHashingTest {

    private static final TenantId TENANT = TenantId.parse("01950f47-6000-7000-8000-000000000001");

    @Test
    void reproducesTheApprovedNewChainSeed() {
        AuditHash seed =
                AuditHashing.chainSeed(
                        TENANT,
                        RetentionClass.GENERAL_AUDIT_EVENT,
                        YearMonth.of(2026, 10),
                        7,
                        64,
                        new CanonicalJsonCodec());

        assertThat(seed.hex())
                .isEqualTo("c70c0a53fb776859b8d64327546965ab9fc8e971b9890f6cf99a0352ab122cc7");
        assertThat(seed.hashAlgorithmVersion()).isEqualTo((short) 1);
    }

    @Test
    void bindsARecordToItsPredecessor() {
        CanonicalDocument event =
                new CanonicalJsonCodec()
                        .encode(new ObjectValue(Map.of("event", new StringValue("completed"))));
        AuditHash first =
                AuditHashing.recordHash(
                        new AuditHash(
                                (short) 1,
                                "01234567890123456789012345678901"
                                        .getBytes(StandardCharsets.US_ASCII)),
                        event);
        AuditHash second =
                AuditHashing.recordHash(
                        new AuditHash(
                                (short) 1,
                                "11234567890123456789012345678901"
                                        .getBytes(StandardCharsets.US_ASCII)),
                        event);

        assertThat(first).isNotEqualTo(second);
    }
}
