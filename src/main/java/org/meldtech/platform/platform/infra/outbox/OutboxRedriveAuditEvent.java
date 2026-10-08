package org.meldtech.platform.platform.infra.outbox;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.meldtech.platform.outbox.api.RedriveResult;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;

final class OutboxRedriveAuditEvent {

    private OutboxRedriveAuditEvent() {}

    static AuditEvent create(
            UUID requestId,
            UUID outboxEventId,
            String reason,
            String sourceKind,
            RedriveResult outcome) {
        return new AuditEvent(
                "platform.OUTBOX_REDRIVE_COMPLETED.v1",
                new EntityRef("outbox.event", outboxEventId.toString()),
                Set.of(RetentionClass.GENERAL_AUDIT_EVENT),
                new ObjectValue(
                        Map.of(
                                "request_id", new StringValue(requestId.toString()),
                                "reason", new StringValue(reason),
                                "source_kind", new StringValue(sourceKind),
                                "outcome", new StringValue(outcome.name()))));
    }
}
