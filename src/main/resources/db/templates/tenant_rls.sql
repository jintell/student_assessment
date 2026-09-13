-- Replace ${schema} and ${table} with lower-case identifiers in the owning migration.
ALTER TABLE ${schema}.${table} ENABLE ROW LEVEL SECURITY;
ALTER TABLE ${schema}.${table} FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON ${schema}.${table}
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', false)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid);
