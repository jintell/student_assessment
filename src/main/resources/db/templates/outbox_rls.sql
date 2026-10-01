-- Outbox specialization of tenant_rls.sql. Replace ${module_roles} with the
-- closed module-role list; keep both predicates strict and fail-closed.
ALTER TABLE outbox.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox.outbox_event FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_outbox_write ON outbox.outbox_event
    AS PERMISSIVE
    FOR ALL
    TO ${module_roles}
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
