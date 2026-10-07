-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 scope retention deletion to an expired detached audit partition

GRANT USAGE ON SCHEMA audit TO app_audit_retention;
REVOKE DELETE ON ALL TABLES IN SCHEMA audit FROM app_audit_retention;

CREATE FUNCTION audit.authorize_retention_delete(
    partition_relation regclass,
    approved_due_at timestamp with time zone
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    partition_name name;
    partition_schema name;
BEGIN
    IF approved_due_at IS NULL OR approved_due_at > CURRENT_TIMESTAMP THEN
        RAISE EXCEPTION 'retention deletion requires an expired disposition approval'
            USING ERRCODE = '55000';
    END IF;

    SELECT namespace.nspname, relation.relname
    INTO partition_schema, partition_name
    FROM pg_catalog.pg_class AS relation
    JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    WHERE relation.oid = partition_relation
      AND relation.relkind = 'r';

    IF partition_schema <> 'audit'
            OR partition_name::text !~ '^audit_event_p_(result_correction|result_publication|pin_security|general)_y[0-9]{4}m(0[1-9]|1[0-2])$' THEN
        RAISE EXCEPTION 'retention deletion target is not an audit event leaf partition'
            USING ERRCODE = '22023';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_inherits
        WHERE inhrelid = partition_relation
    ) THEN
        RAISE EXCEPTION 'retention deletion target must be detached first'
            USING ERRCODE = '55000';
    END IF;

    EXECUTE format(
        'GRANT DELETE ON TABLE %I.%I TO app_audit_retention',
        partition_schema,
        partition_name
    );
END;
$$;

REVOKE ALL ON FUNCTION audit.authorize_retention_delete(
    regclass,
    timestamp with time zone
) FROM PUBLIC;
