-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 enforce table-specific immutable evidence and anchor grants

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_audit_sealer') THEN
        CREATE ROLE app_audit_sealer NOLOGIN NOINHERIT NOCREATEROLE;
    END IF;
    IF NOT EXISTS (
        SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_audit_partition_maintenance'
    ) THEN
        CREATE ROLE app_audit_partition_maintenance NOLOGIN NOINHERIT NOCREATEROLE;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_audit_retention') THEN
        CREATE ROLE app_audit_retention NOLOGIN NOINHERIT NOCREATEROLE;
    END IF;
END
$$;

CREATE FUNCTION audit.reject_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'audit events are immutable'
        USING ERRCODE = '23000';
END;
$$;

CREATE TRIGGER audit_event_immutable
BEFORE UPDATE OR DELETE ON audit.audit_event
FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_event_mutation();

REVOKE ALL ON FUNCTION audit.reject_audit_event_mutation() FROM PUBLIC;

REVOKE INSERT ON TABLE
    audit.audit_chain_head,
    audit.audit_chain_checkpoint,
    audit.audit_chain_root_head,
    audit.audit_chain_seal
FROM
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

GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO
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

GRANT USAGE ON SCHEMA audit TO app_audit_sealer;
GRANT SELECT ON TABLE audit.audit_chain_head TO app_audit_sealer;
GRANT INSERT ON TABLE audit.audit_chain_checkpoint TO app_audit_sealer;
GRANT SELECT, INSERT ON TABLE audit.audit_chain_seal TO app_audit_sealer;
GRANT SELECT, UPDATE ON TABLE audit.audit_chain_root_head TO app_audit_sealer;

GRANT USAGE ON SCHEMA audit TO app_audit_partition_maintenance;
GRANT EXECUTE ON FUNCTION audit.provision_audit_month(date)
    TO app_audit_partition_maintenance;
GRANT EXECUTE ON FUNCTION audit.provision_audit_epoch_heads(
    uuid,
    text,
    date,
    integer,
    smallint
) TO app_audit_partition_maintenance;
GRANT EXECUTE ON FUNCTION audit.provision_future_epoch_heads(date)
    TO app_audit_partition_maintenance;
GRANT EXECUTE ON FUNCTION audit.provision_audit_root_head(uuid)
    TO app_audit_partition_maintenance;
