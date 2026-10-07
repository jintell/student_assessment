-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 index the new empty partitioned audit store for compliance queries

-- PostgreSQL cannot build an index concurrently on a partitioned parent. The
-- audit store and all leaves are new and empty in this expand release. Defining
-- each index on the parent also makes future provisioned leaves inherit it.
-- cbt:index-build NEW_EMPTY_PARTITIONED_PARENT
CREATE INDEX audit_event_tenant_occurred_idx
    ON audit.audit_event (tenant_id, occurred_at DESC);

-- cbt:index-build NEW_EMPTY_PARTITIONED_PARENT
CREATE INDEX audit_event_tenant_entity_idx
    ON audit.audit_event (tenant_id, entity_type, entity_id);

-- cbt:index-build NEW_EMPTY_PARTITIONED_PARENT
CREATE INDEX audit_event_tenant_event_type_occurred_idx
    ON audit.audit_event (tenant_id, event_type, occurred_at DESC);
