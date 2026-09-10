CREATE TABLE platform.tenant_scope_probe (
    tenant_id uuid NOT NULL,
    probe_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, probe_id)
);

COMMENT ON TABLE platform.tenant_scope_probe IS
    'FEAT-PLAT-002 conformance artifact; contains no business or personal data';

ALTER TABLE platform.tenant_scope_probe ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_scope_probe FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON platform.tenant_scope_probe
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', false)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid);
