-- cbt:phase EXPAND
-- cbt:module outbox
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 index the empty partitioned outbox parent

-- PostgreSQL cannot build an index concurrently on a partitioned parent. The
-- parent is empty at this point in the same migration release, and partitions
-- created later inherit these index definitions.
CREATE INDEX outbox_event_pending_claim_idx
    ON outbox.outbox_event (state, created_at, outbox_event_id)
    WHERE state = 'PENDING';

CREATE INDEX outbox_event_claim_reclamation_idx
    ON outbox.outbox_event (state, claim_expires_at)
    WHERE state = 'CLAIMED';

CREATE INDEX outbox_event_tenant_aggregate_idx
    ON outbox.outbox_event (tenant_id, aggregate_id, created_at);
