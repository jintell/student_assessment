-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 persist append-only per-tenant shard topology by epoch boundary

CREATE TABLE audit.audit_shard_policy (
    tenant_id uuid NOT NULL,
    effective_period date NOT NULL,
    shard_count integer NOT NULL,
    policy_version bigint NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_shard_policy_pk PRIMARY KEY (tenant_id, effective_period),
    CONSTRAINT audit_shard_policy_version_unique UNIQUE (tenant_id, policy_version),
    CONSTRAINT audit_shard_policy_period CHECK (
        effective_period = date_trunc('month', effective_period)::date
    ),
    CONSTRAINT audit_shard_policy_count CHECK (
        shard_count > 0 AND shard_count <= 1024
    ),
    CONSTRAINT audit_shard_policy_version CHECK (policy_version > 0)
);

COMMENT ON TABLE audit.audit_shard_policy IS
    'Append-only tenant audit topology; each row becomes effective at one UTC epoch boundary';

ALTER TABLE audit.audit_shard_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_shard_policy FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON audit.audit_shard_policy
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY audit_migrator_maintenance ON audit.audit_shard_policy
    AS PERMISSIVE
    FOR ALL
    TO app_migrator
    USING (current_user = 'app_migrator')
    WITH CHECK (current_user = 'app_migrator');

REVOKE INSERT ON TABLE audit.audit_shard_policy FROM
    app_tenancy,
    app_iam,
    app_academic,
    app_people,
    app_questionbank,
    app_authoring,
    app_examaccess,
    app_delivery,
    app_grading,
    app_result,
    app_correction,
    app_notification;

GRANT SELECT, INSERT ON TABLE audit.audit_shard_policy
    TO app_audit_partition_maintenance;
