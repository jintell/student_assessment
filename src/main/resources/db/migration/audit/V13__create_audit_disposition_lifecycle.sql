-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 persist partition-granular disposition and legal-hold state

CREATE TABLE audit.audit_disposition_lifecycle (
    request_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    retention_class varchar(48) NOT NULL,
    period date NOT NULL,
    policy_key varchar(128) NOT NULL,
    policy_version bigint NOT NULL,
    state varchar(32) NOT NULL,
    original_retention_start timestamp with time zone NOT NULL,
    original_due_at timestamp with time zone NOT NULL,
    hold_references jsonb NOT NULL DEFAULT '[]'::jsonb,
    hold_detected_at timestamp with time zone,
    released_at timestamp with time zone,
    last_completed_step varchar(64),
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT audit_disposition_lifecycle_epoch_policy_unique UNIQUE (
        tenant_id, retention_class, period, policy_version
    ),
    CONSTRAINT audit_disposition_lifecycle_retention_class CHECK (
        retention_class IN (
            'RESULT_CORRECTION_EVIDENCE',
            'RESULT_PUBLICATION_EVIDENCE',
            'PIN_SECURITY_EVENT',
            'GENERAL_AUDIT_EVENT'
        )
    ),
    CONSTRAINT audit_disposition_lifecycle_period CHECK (
        period = date_trunc('month', period)::date
    ),
    CONSTRAINT audit_disposition_lifecycle_policy CHECK (
        btrim(policy_key) <> '' AND policy_version > 0
    ),
    CONSTRAINT audit_disposition_lifecycle_state CHECK (
        state IN ('ELIGIBLE', 'HOLD_SUSPENDED', 'IN_PROGRESS', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT audit_disposition_lifecycle_due CHECK (
        original_due_at >= original_retention_start
    ),
    CONSTRAINT audit_disposition_lifecycle_holds CHECK (
        jsonb_typeof(hold_references) = 'array'
    ),
    CONSTRAINT audit_disposition_lifecycle_hold_shape CHECK (
        (state = 'HOLD_SUSPENDED'
            AND jsonb_array_length(hold_references) > 0
            AND hold_detected_at IS NOT NULL)
        OR state <> 'HOLD_SUSPENDED'
    )
);

CREATE INDEX audit_disposition_lifecycle_tenant_state_due_idx
    ON audit.audit_disposition_lifecycle (tenant_id, state, original_due_at);

ALTER TABLE audit.audit_disposition_lifecycle ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_disposition_lifecycle FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON audit.audit_disposition_lifecycle
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY audit_migrator_maintenance ON audit.audit_disposition_lifecycle
    AS PERMISSIVE
    FOR ALL
    TO app_migrator
    USING (current_user = 'app_migrator')
    WITH CHECK (current_user = 'app_migrator');

REVOKE INSERT ON TABLE audit.audit_disposition_lifecycle FROM
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

GRANT SELECT, INSERT, UPDATE ON TABLE audit.audit_disposition_lifecycle
    TO app_audit_retention;
