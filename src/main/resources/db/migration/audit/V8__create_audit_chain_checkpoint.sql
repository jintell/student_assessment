-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 create immutable KMS-signed shard checkpoints

CREATE TABLE audit.audit_chain_checkpoint (
    tenant_id uuid NOT NULL,
    retention_class varchar(48) NOT NULL,
    period date NOT NULL,
    shard_id integer NOT NULL,
    shard_count integer NOT NULL,
    seq_start bigint NOT NULL,
    seq_end bigint NOT NULL,
    record_count bigint NOT NULL,
    head_hash bytea NOT NULL,
    hash_algo_version smallint NOT NULL,
    event_occurred_from timestamp with time zone NOT NULL,
    event_occurred_to timestamp with time zone NOT NULL,
    signing_key_version varchar(256) NOT NULL,
    signature_algorithm varchar(64) NOT NULL,
    signature bytea NOT NULL,
    signature_request_id varchar(256) NOT NULL,
    signed_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_chain_checkpoint_pk PRIMARY KEY (
        tenant_id,
        retention_class,
        period,
        shard_id,
        seq_end
    ),
    CONSTRAINT audit_chain_checkpoint_retention_class CHECK (
        retention_class IN (
            'RESULT_CORRECTION_EVIDENCE',
            'RESULT_PUBLICATION_EVIDENCE',
            'PIN_SECURITY_EVENT',
            'GENERAL_AUDIT_EVENT'
        )
    ),
    CONSTRAINT audit_chain_checkpoint_period_utc_month CHECK (
        period = date_trunc('month', period)::date
    ),
    CONSTRAINT audit_chain_checkpoint_shard_range CHECK (
        shard_count > 0 AND shard_id >= 0 AND shard_id < shard_count
    ),
    CONSTRAINT audit_chain_checkpoint_sequence_range CHECK (
        seq_start > 0 AND seq_end >= seq_start
    ),
    CONSTRAINT audit_chain_checkpoint_record_count CHECK (
        record_count = seq_end - seq_start + 1
    ),
    CONSTRAINT audit_chain_checkpoint_head_hash_sha256 CHECK (
        octet_length(head_hash) = 32
    ),
    CONSTRAINT audit_chain_checkpoint_hash_version_positive CHECK (
        hash_algo_version > 0
    ),
    CONSTRAINT audit_chain_checkpoint_event_time_range CHECK (
        event_occurred_to >= event_occurred_from
    ),
    CONSTRAINT audit_chain_checkpoint_key_version_not_blank CHECK (
        btrim(signing_key_version) <> ''
    ),
    CONSTRAINT audit_chain_checkpoint_signature_algorithm_not_blank CHECK (
        btrim(signature_algorithm) <> ''
    ),
    CONSTRAINT audit_chain_checkpoint_signature_not_empty CHECK (
        octet_length(signature) > 0
    ),
    CONSTRAINT audit_chain_checkpoint_request_id_not_blank CHECK (
        btrim(signature_request_id) <> ''
    )
);

COMMENT ON TABLE audit.audit_chain_checkpoint IS
    'Immutable signed prefix evidence for one audit shard chain';
