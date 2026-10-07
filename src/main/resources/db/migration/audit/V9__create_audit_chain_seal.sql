-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 create permanent signed epoch seals for the tenant root chain

CREATE TABLE audit.audit_chain_seal (
    tenant_id uuid NOT NULL,
    retention_class varchar(48) NOT NULL,
    period date NOT NULL,
    root_seq bigint NOT NULL,
    previous_root_hash bytea NOT NULL,
    epoch_root bytea NOT NULL,
    shard_count integer NOT NULL,
    per_shard_counts jsonb NOT NULL,
    sequence_ranges jsonb NOT NULL,
    hash_algo_version smallint NOT NULL,
    signing_key_version varchar(256) NOT NULL,
    signature_algorithm varchar(64) NOT NULL,
    signature bytea NOT NULL,
    signature_request_id varchar(256) NOT NULL,
    signed_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_chain_seal_pk
        PRIMARY KEY (tenant_id, retention_class, period),
    CONSTRAINT audit_chain_seal_root_seq_unique UNIQUE (tenant_id, root_seq),
    CONSTRAINT audit_chain_seal_retention_class CHECK (
        retention_class IN (
            'RESULT_CORRECTION_EVIDENCE',
            'RESULT_PUBLICATION_EVIDENCE',
            'PIN_SECURITY_EVENT',
            'GENERAL_AUDIT_EVENT'
        )
    ),
    CONSTRAINT audit_chain_seal_period_utc_month CHECK (
        period = date_trunc('month', period)::date
    ),
    CONSTRAINT audit_chain_seal_root_seq_positive CHECK (root_seq > 0),
    CONSTRAINT audit_chain_seal_previous_root_hash_sha256 CHECK (
        octet_length(previous_root_hash) = 32
    ),
    CONSTRAINT audit_chain_seal_epoch_root_sha256 CHECK (
        octet_length(epoch_root) = 32
    ),
    CONSTRAINT audit_chain_seal_shard_count_positive CHECK (shard_count > 0),
    CONSTRAINT audit_chain_seal_per_shard_counts_shape CHECK (
        jsonb_typeof(per_shard_counts) = 'array'
        AND jsonb_array_length(per_shard_counts) = shard_count
    ),
    CONSTRAINT audit_chain_seal_sequence_ranges_shape CHECK (
        jsonb_typeof(sequence_ranges) = 'array'
        AND jsonb_array_length(sequence_ranges) = shard_count
    ),
    CONSTRAINT audit_chain_seal_hash_version_positive CHECK (hash_algo_version > 0),
    CONSTRAINT audit_chain_seal_key_version_not_blank CHECK (
        btrim(signing_key_version) <> ''
    ),
    CONSTRAINT audit_chain_seal_signature_algorithm_not_blank CHECK (
        btrim(signature_algorithm) <> ''
    ),
    CONSTRAINT audit_chain_seal_signature_not_empty CHECK (
        octet_length(signature) > 0
    ),
    CONSTRAINT audit_chain_seal_request_id_not_blank CHECK (
        btrim(signature_request_id) <> ''
    )
);

COMMENT ON TABLE audit.audit_chain_seal IS
    'Permanent KMS-signed epoch root; retained after event-partition disposition';
