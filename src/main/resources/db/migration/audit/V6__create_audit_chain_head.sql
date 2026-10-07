-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 create and pre-provision deterministic audit shard heads

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA audit;

CREATE TABLE audit.audit_chain_head (
    tenant_id uuid NOT NULL,
    retention_class varchar(48) NOT NULL,
    period date NOT NULL,
    shard_id integer NOT NULL,
    shard_count integer NOT NULL,
    seq bigint NOT NULL DEFAULT 0,
    head_hash bytea NOT NULL,
    hash_algo_version smallint NOT NULL DEFAULT 1,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT audit_chain_head_pk
        PRIMARY KEY (tenant_id, retention_class, period, shard_id),
    CONSTRAINT audit_chain_head_retention_class CHECK (
        retention_class IN (
            'RESULT_CORRECTION_EVIDENCE',
            'RESULT_PUBLICATION_EVIDENCE',
            'PIN_SECURITY_EVENT',
            'GENERAL_AUDIT_EVENT'
        )
    ),
    CONSTRAINT audit_chain_head_period_utc_month CHECK (
        period = date_trunc('month', period)::date
    ),
    CONSTRAINT audit_chain_head_shard_count_positive CHECK (shard_count > 0),
    CONSTRAINT audit_chain_head_shard_range CHECK (
        shard_id >= 0 AND shard_id < shard_count
    ),
    CONSTRAINT audit_chain_head_seq_non_negative CHECK (seq >= 0),
    CONSTRAINT audit_chain_head_hash_sha256 CHECK (octet_length(head_hash) = 32),
    CONSTRAINT audit_chain_head_hash_version_positive CHECK (hash_algo_version > 0)
);

CREATE FUNCTION audit.audit_chain_seed(
    target_tenant_id uuid,
    target_retention_class text,
    target_period date,
    target_shard_id integer,
    target_shard_count integer
)
RETURNS bytea
LANGUAGE sql
IMMUTABLE
STRICT
SET search_path = pg_catalog, pg_temp
RETURN audit.digest(
    convert_to('meldtech.audit.chain.seed.v1', 'UTF8')
    || decode('00', 'hex')
    || convert_to(
        format(
            '{"period":"%s","retention_class":"%s","shard_count":%s,'
            || '"shard_id":%s,"tenant_id":"%s"}',
            to_char(target_period, 'YYYY-MM'),
            target_retention_class,
            target_shard_count,
            target_shard_id,
            target_tenant_id
        ),
        'UTF8'
    ),
    'sha256'
);

CREATE FUNCTION audit.provision_audit_epoch_heads(
    target_tenant_id uuid,
    target_retention_class text,
    target_period date,
    target_shard_count integer,
    target_hash_algo_version smallint
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    class_token text;
    expected_seed bytea;
    partition_name name;
    provisioned_shard integer;
BEGIN
    IF target_tenant_id IS NULL THEN
        RAISE EXCEPTION 'target_tenant_id is required' USING ERRCODE = '22004';
    END IF;
    IF target_retention_class NOT IN (
        'RESULT_CORRECTION_EVIDENCE',
        'RESULT_PUBLICATION_EVIDENCE',
        'PIN_SECURITY_EVENT',
        'GENERAL_AUDIT_EVENT'
    ) THEN
        RAISE EXCEPTION 'unknown retention class' USING ERRCODE = '22023';
    END IF;
    IF target_period IS NULL OR target_period <> date_trunc('month', target_period)::date THEN
        RAISE EXCEPTION 'target_period must be the first day of a UTC month'
            USING ERRCODE = '22023';
    END IF;
    IF target_shard_count IS NULL OR target_shard_count <= 0 THEN
        RAISE EXCEPTION 'target_shard_count must be positive' USING ERRCODE = '22023';
    END IF;
    IF target_hash_algo_version IS NULL OR target_hash_algo_version <= 0 THEN
        RAISE EXCEPTION 'target_hash_algo_version must be positive' USING ERRCODE = '22023';
    END IF;

    class_token := CASE target_retention_class
        WHEN 'RESULT_CORRECTION_EVIDENCE' THEN 'result_correction'
        WHEN 'RESULT_PUBLICATION_EVIDENCE' THEN 'result_publication'
        WHEN 'PIN_SECURITY_EVENT' THEN 'pin_security'
        WHEN 'GENERAL_AUDIT_EVENT' THEN 'general'
    END;
    partition_name := (
        'audit_event_p_' || class_token || '_y' || to_char(target_period, 'YYYY')
        || 'm' || to_char(target_period, 'MM')
    )::name;
    IF to_regclass(format('audit.%I', partition_name)) IS NULL THEN
        RAISE EXCEPTION 'audit event partition must exist before chain-head provisioning'
            USING ERRCODE = '55000';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(target_tenant_id::text, 363004));

    FOR provisioned_shard IN 0..target_shard_count - 1 LOOP
        expected_seed := audit.audit_chain_seed(
            target_tenant_id,
            target_retention_class,
            target_period,
            provisioned_shard,
            target_shard_count
        );

        INSERT INTO audit.audit_chain_head (
            tenant_id,
            retention_class,
            period,
            shard_id,
            shard_count,
            seq,
            head_hash,
            hash_algo_version
        )
        VALUES (
            target_tenant_id,
            target_retention_class,
            target_period,
            provisioned_shard,
            target_shard_count,
            0,
            expected_seed,
            target_hash_algo_version
        )
        ON CONFLICT (tenant_id, retention_class, period, shard_id) DO NOTHING;

        IF NOT EXISTS (
            SELECT 1
            FROM audit.audit_chain_head AS existing_head
            WHERE existing_head.tenant_id = target_tenant_id
              AND existing_head.retention_class = target_retention_class
              AND existing_head.period = target_period
              AND existing_head.shard_id = provisioned_shard
              AND existing_head.shard_count = target_shard_count
              AND existing_head.seq = 0
              AND existing_head.head_hash = expected_seed
              AND existing_head.hash_algo_version = target_hash_algo_version
        ) THEN
            RAISE EXCEPTION 'existing audit chain head conflicts with requested topology'
                USING ERRCODE = '23000';
        END IF;
    END LOOP;
END;
$$;

CREATE FUNCTION audit.provision_future_epoch_heads(target_month date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    tenant_topology record;
    target_retention_class text;
BEGIN
    FOR tenant_topology IN
        SELECT DISTINCT ON (tenant_id)
            tenant_id,
            shard_count,
            hash_algo_version
        FROM audit.audit_chain_head
        ORDER BY tenant_id, period DESC
    LOOP
        FOREACH target_retention_class IN ARRAY ARRAY[
            'RESULT_CORRECTION_EVIDENCE',
            'RESULT_PUBLICATION_EVIDENCE',
            'PIN_SECURITY_EVENT',
            'GENERAL_AUDIT_EVENT'
        ] LOOP
            PERFORM audit.provision_audit_epoch_heads(
                tenant_topology.tenant_id,
                target_retention_class,
                target_month,
                tenant_topology.shard_count,
                tenant_topology.hash_algo_version
            );
        END LOOP;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION audit.audit_chain_seed(uuid, text, date, integer, integer)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION audit.provision_audit_epoch_heads(uuid, text, date, integer, smallint)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION audit.provision_future_epoch_heads(date) FROM PUBLIC;

SELECT audit.provision_future_epoch_heads(
    date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
);
SELECT audit.provision_future_epoch_heads(
    (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + interval '1 month')::date
);
SELECT audit.provision_future_epoch_heads(
    (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + interval '2 months')::date
);
SELECT audit.provision_future_epoch_heads(
    (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + interval '3 months')::date
);
