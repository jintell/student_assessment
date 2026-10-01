-- cbt:phase EXPAND
-- cbt:module outbox
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 enforce tenant writes and closed relay access

-- Expanded from db/templates/outbox_rls.sql, which preserves the strict
-- tenant predicate from db/templates/tenant_rls.sql.
ALTER TABLE outbox.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox.outbox_event FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_outbox_write ON outbox.outbox_event
    AS PERMISSIVE
    FOR ALL
    TO app_tenancy, app_iam, app_academic, app_people,
       app_questionbank, app_authoring, app_examaccess, app_delivery,
       app_grading, app_result, app_correction, app_notification,
       app_txn_examentry
    USING (tenant_id = current_setting('app.tenant_id', false)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid);

CREATE POLICY outbox_relay_drain ON outbox.outbox_event
    AS PERMISSIVE
    FOR ALL
    TO app_outbox_relay
    USING (
        current_user = 'app_outbox_relay'
        AND current_setting('app.platform_scope', false) = 'outbox_relay'
    )
    WITH CHECK (
        current_user = 'app_outbox_relay'
        AND current_setting('app.platform_scope', false) = 'outbox_relay'
    );
