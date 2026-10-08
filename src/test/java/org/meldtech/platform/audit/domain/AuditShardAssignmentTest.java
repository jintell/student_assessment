package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.EntityRef;

class AuditShardAssignmentTest {

    @Test
    void assignsAnEntityUsingTheApprovedUnsignedDigestRule() {
        EntityRef entity = new EntityRef("outbox.event", "event-123");

        assertThat(AuditShardAssignment.shardFor(entity, 64)).isEqualTo(18);
        assertThat(AuditShardAssignment.shardFor(entity, 64)).isEqualTo(18);
    }
}
