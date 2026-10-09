package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;

class AuditShardAssignmentTest {

    @Test
    void assignsAnEntityUsingTheApprovedUnsignedDigestRule() {
        EntityRef entity = new EntityRef("outbox.event", "event-123");

        assertThat(AuditShardAssignment.shardFor(entity, 64)).isEqualTo(18);
        assertThat(AuditShardAssignment.shardFor(entity, 64)).isEqualTo(18);
    }

    @Test
    void relatedEventsShareTheirEntityShard() {
        EntityRef entity = new EntityRef("assessment.attempt", "attempt-123");
        AuditEvent created =
                new AuditEvent(
                        "assessment.ATTEMPT_CREATED.v1",
                        entity,
                        Set.of(RetentionClass.GENERAL_AUDIT_EVENT),
                        new ObjectValue(Map.of()));
        AuditEvent submitted =
                new AuditEvent(
                        "assessment.ATTEMPT_SUBMITTED.v1",
                        entity,
                        Set.of(RetentionClass.GENERAL_AUDIT_EVENT),
                        new ObjectValue(Map.of()));
        assertThat(AuditShardAssignment.shardFor(created.entity(), 64))
                .isEqualTo(AuditShardAssignment.shardFor(submitted.entity(), 64));
    }

    @Test
    void manyEntitiesStayWithinTheSkewBudget(TestReporter reporter) {
        int shardCount = 64;
        int entityCount = 64_000;
        int[] counts = new int[shardCount];
        for (int entity = 0; entity < entityCount; entity++) {
            counts[
                    AuditShardAssignment.shardFor(
                            new EntityRef("assessment.attempt", "attempt-" + entity),
                            shardCount)]++;
        }
        int minimum = Arrays.stream(counts).min().orElseThrow();
        int maximum = Arrays.stream(counts).max().orElseThrow();
        double maxToMean = maximum / ((double) entityCount / shardCount);
        reporter.publishEntry(
                Map.of(
                        "entities",
                        Integer.toString(entityCount),
                        "shards",
                        Integer.toString(shardCount),
                        "minimum",
                        Integer.toString(minimum),
                        "maximum",
                        Integer.toString(maximum),
                        "maxToMean",
                        Double.toString(maxToMean)));
        assertThat(Arrays.stream(counts).sum()).isEqualTo(entityCount);
        assertThat(minimum).isGreaterThan(850);
        assertThat(maxToMean).isLessThan(1.15);
    }
}
