-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 create the immutable partitioned audit event store

CREATE TABLE audit.audit_event (
    audit_event_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    entity_type varchar(128) NOT NULL,
    entity_id text NOT NULL,
    actor_type varchar(32) NOT NULL,
    actor_id varchar(128) NOT NULL,
    system_actor_name varchar(64),
    tenant_id uuid,
    occurred_at timestamp with time zone NOT NULL,
    correlation_id varchar(26) NOT NULL,
    retention_class varchar(48) NOT NULL,
    retention_policy_key varchar(128) NOT NULL,
    retention_policy_version bigint NOT NULL,
    retention_until timestamp with time zone,
    period date NOT NULL,
    shard_id integer NOT NULL,
    seq bigint NOT NULL,
    prev_hash bytea NOT NULL,
    record_hash bytea NOT NULL,
    hash_algo_version smallint NOT NULL,
    payload jsonb NOT NULL,
    CONSTRAINT audit_event_pk
        PRIMARY KEY (retention_class, occurred_at, audit_event_id),
    CONSTRAINT audit_event_type_format CHECK (
        event_type ~ '^[a-z][a-z0-9]*\.[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*\.v[1-9][0-9]*$'
    ),
    CONSTRAINT audit_event_entity_type_not_blank CHECK (btrim(entity_type) <> ''),
    CONSTRAINT audit_event_entity_id_not_blank CHECK (btrim(entity_id) <> ''),
    CONSTRAINT audit_event_actor_type CHECK (
        actor_type IN ('WORKFORCE_USER', 'CANDIDATE', 'SYSTEM')
    ),
    CONSTRAINT audit_event_actor_id_format CHECK (
        actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    CONSTRAINT audit_event_system_actor_shape CHECK (
        (actor_type = 'SYSTEM' AND system_actor_name IS NOT NULL)
        OR
        (actor_type <> 'SYSTEM' AND system_actor_name IS NULL)
    ),
    CONSTRAINT audit_event_candidate_tenant CHECK (
        actor_type <> 'CANDIDATE' OR tenant_id IS NOT NULL
    ),
    CONSTRAINT audit_event_correlation_id_format CHECK (
        correlation_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'
    ),
    CONSTRAINT audit_event_retention_class CHECK (
        retention_class IN (
            'RESULT_CORRECTION_EVIDENCE',
            'RESULT_PUBLICATION_EVIDENCE',
            'PIN_SECURITY_EVENT',
            'GENERAL_AUDIT_EVENT'
        )
    ),
    CONSTRAINT audit_event_policy_key_not_blank CHECK (btrim(retention_policy_key) <> ''),
    CONSTRAINT audit_event_policy_version_positive CHECK (retention_policy_version > 0),
    CONSTRAINT audit_event_period_utc_month CHECK (
        period = date_trunc('month', occurred_at AT TIME ZONE 'UTC')::date
    ),
    CONSTRAINT audit_event_shard_non_negative CHECK (shard_id >= 0),
    CONSTRAINT audit_event_seq_positive CHECK (seq > 0),
    CONSTRAINT audit_event_prev_hash_sha256 CHECK (octet_length(prev_hash) = 32),
    CONSTRAINT audit_event_record_hash_sha256 CHECK (octet_length(record_hash) = 32),
    CONSTRAINT audit_event_hash_version_positive CHECK (hash_algo_version > 0),
    CONSTRAINT audit_event_payload_object CHECK (jsonb_typeof(payload) = 'object')
) PARTITION BY LIST (retention_class);

COMMENT ON TABLE audit.audit_event IS
    'Immutable audit evidence, physically placed by retention class and UTC month';
COMMENT ON COLUMN audit.audit_event.tenant_id IS
    'Null only for an event type explicitly registered as platform scope';
COMMENT ON COLUMN audit.audit_event.retention_class IS
    'Write-time physical placement decision; never recomputed by a query';
