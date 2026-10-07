-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 enforce tenant isolation on audit evidence and anchors

ALTER TABLE audit.audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_event FORCE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_chain_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_chain_head FORCE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_chain_root_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_chain_root_head FORCE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_chain_checkpoint ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_chain_checkpoint FORCE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_chain_seal ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_chain_seal FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON audit.audit_event
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY audit_platform_scope ON audit.audit_event
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (
        tenant_id IS NULL
        AND current_setting('app.platform_scope', true) = 'audit'
    )
    WITH CHECK (
        tenant_id IS NULL
        AND current_setting('app.platform_scope', true) = 'audit'
    );

CREATE POLICY audit_migrator_maintenance ON audit.audit_event
    AS PERMISSIVE
    FOR ALL
    TO app_migrator
    USING (current_user = 'app_migrator')
    WITH CHECK (current_user = 'app_migrator');

CREATE POLICY tenant_isolation ON audit.audit_chain_head
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY audit_migrator_maintenance ON audit.audit_chain_head
    AS PERMISSIVE
    FOR ALL
    TO app_migrator
    USING (current_user = 'app_migrator')
    WITH CHECK (current_user = 'app_migrator');

CREATE POLICY tenant_isolation ON audit.audit_chain_root_head
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY audit_migrator_maintenance ON audit.audit_chain_root_head
    AS PERMISSIVE
    FOR ALL
    TO app_migrator
    USING (current_user = 'app_migrator')
    WITH CHECK (current_user = 'app_migrator');

CREATE POLICY tenant_isolation ON audit.audit_chain_checkpoint
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY audit_migrator_maintenance ON audit.audit_chain_checkpoint
    AS PERMISSIVE
    FOR ALL
    TO app_migrator
    USING (current_user = 'app_migrator')
    WITH CHECK (current_user = 'app_migrator');

CREATE POLICY tenant_isolation ON audit.audit_chain_seal
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY audit_migrator_maintenance ON audit.audit_chain_seal
    AS PERMISSIVE
    FOR ALL
    TO app_migrator
    USING (current_user = 'app_migrator')
    WITH CHECK (current_user = 'app_migrator');

CREATE FUNCTION audit.secure_audit_event_partition(target_partition regclass)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    qualified_partition text;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_partition_tree('audit.audit_event'::regclass)
        WHERE relid = target_partition
          AND level = 2
    ) THEN
        RAISE EXCEPTION 'target relation is not an audit event leaf partition'
            USING ERRCODE = '22023';
    END IF;

    SELECT format('%I.%I', namespace.nspname, relation.relname)
    INTO STRICT qualified_partition
    FROM pg_catalog.pg_class AS relation
    JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    WHERE relation.oid = target_partition;

    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', qualified_partition);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', qualified_partition);

    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_policy
        WHERE polrelid = target_partition AND polname = 'tenant_isolation'
    ) THEN
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %s AS PERMISSIVE FOR ALL TO PUBLIC '
            || 'USING (tenant_id = current_setting(''app.tenant_id'', true)::uuid) '
            || 'WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true)::uuid)',
            qualified_partition
        );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_policy
        WHERE polrelid = target_partition AND polname = 'audit_platform_scope'
    ) THEN
        EXECUTE format(
            'CREATE POLICY audit_platform_scope ON %s AS PERMISSIVE FOR ALL TO PUBLIC '
            || 'USING (tenant_id IS NULL AND '
            || 'current_setting(''app.platform_scope'', true) = ''audit'') '
            || 'WITH CHECK (tenant_id IS NULL AND '
            || 'current_setting(''app.platform_scope'', true) = ''audit'')',
            qualified_partition
        );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_policy
        WHERE polrelid = target_partition AND polname = 'audit_migrator_maintenance'
    ) THEN
        EXECUTE format(
            'CREATE POLICY audit_migrator_maintenance ON %s AS PERMISSIVE FOR ALL '
            || 'TO app_migrator USING (current_user = ''app_migrator'') '
            || 'WITH CHECK (current_user = ''app_migrator'')',
            qualified_partition
        );
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION audit.secure_audit_event_partition(regclass) FROM PUBLIC;

CREATE OR REPLACE FUNCTION audit.provision_future_epoch_heads(target_month date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    class_token text;
    partition_name text;
    target_retention_class text;
    tenant_topology record;
BEGIN
    FOREACH class_token IN ARRAY ARRAY[
        'result_correction',
        'result_publication',
        'pin_security',
        'general'
    ] LOOP
        partition_name := (
            'audit.audit_event_p_' || class_token || '_y' || to_char(target_month, 'YYYY')
            || 'm' || to_char(target_month, 'MM')
        );
        PERFORM audit.secure_audit_event_partition(partition_name::regclass);
    END LOOP;

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

DO $$
DECLARE
    leaf_partition regclass;
BEGIN
    FOR leaf_partition IN
        SELECT relid
        FROM pg_catalog.pg_partition_tree('audit.audit_event'::regclass)
        WHERE level = 2
    LOOP
        PERFORM audit.secure_audit_event_partition(leaf_partition);
    END LOOP;
END;
$$;
