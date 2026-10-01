-- Replace {{schema}} and {{module_role}} from the closed consumer registration.
CREATE TABLE {{schema}}.processed_event (
    outbox_event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    event_type text NOT NULL,
    processed_at timestamp with time zone NOT NULL,
    outcome varchar(24) NOT NULL
        CHECK (outcome IN ('APPLIED', 'BUSINESS_DUPLICATE', 'STALE_VERSION'))
);

ALTER TABLE {{schema}}.processed_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE {{schema}}.processed_event FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_processed_event ON {{schema}}.processed_event
    FOR ALL TO {{module_role}}
    USING (tenant_id = current_setting('app.tenant_id', false)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid);
