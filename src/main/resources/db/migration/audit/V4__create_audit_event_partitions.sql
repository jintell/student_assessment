-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 create retention-homogeneous audit partitions and maintenance operation

CREATE TABLE audit.audit_event_p_result_correction
    PARTITION OF audit.audit_event
    FOR VALUES IN ('RESULT_CORRECTION_EVIDENCE')
    PARTITION BY RANGE (occurred_at);

CREATE TABLE audit.audit_event_p_result_publication
    PARTITION OF audit.audit_event
    FOR VALUES IN ('RESULT_PUBLICATION_EVIDENCE')
    PARTITION BY RANGE (occurred_at);

CREATE TABLE audit.audit_event_p_pin_security
    PARTITION OF audit.audit_event
    FOR VALUES IN ('PIN_SECURITY_EVENT')
    PARTITION BY RANGE (occurred_at);

CREATE TABLE audit.audit_event_p_general
    PARTITION OF audit.audit_event
    FOR VALUES IN ('GENERAL_AUDIT_EVENT')
    PARTITION BY RANGE (occurred_at);

CREATE FUNCTION audit.provision_audit_month(target_month date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    class_token text;
    class_tokens constant text[] := ARRAY[
        'result_correction',
        'result_publication',
        'pin_security',
        'general'
    ];
    month_end date;
    partition_name name;
    parent_name name;
BEGIN
    IF target_month IS NULL OR target_month <> date_trunc('month', target_month)::date THEN
        RAISE EXCEPTION 'target_month must be the first day of a UTC month'
            USING ERRCODE = '22023';
    END IF;

    month_end := (target_month + interval '1 month')::date;
    PERFORM pg_advisory_xact_lock(363001, target_month - DATE '2000-01-01');

    FOREACH class_token IN ARRAY class_tokens LOOP
        parent_name := ('audit_event_p_' || class_token)::name;
        partition_name := (
            'audit_event_p_' || class_token || '_y' || to_char(target_month, 'YYYY')
            || 'm' || to_char(target_month, 'MM')
        )::name;

        IF to_regclass(format('audit.%I', partition_name)) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE audit.%I PARTITION OF audit.%I '
                || 'FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                parent_name,
                make_timestamptz(
                    extract(year FROM target_month)::integer,
                    extract(month FROM target_month)::integer,
                    1,
                    0,
                    0,
                    0,
                    'UTC'
                ),
                make_timestamptz(
                    extract(year FROM month_end)::integer,
                    extract(month FROM month_end)::integer,
                    1,
                    0,
                    0,
                    0,
                    'UTC'
                )
            );
        ELSIF NOT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_inherits AS leaf_membership
            JOIN pg_catalog.pg_class AS parent
              ON parent.oid = leaf_membership.inhparent
            JOIN pg_catalog.pg_namespace AS parent_namespace
              ON parent_namespace.oid = parent.relnamespace
            WHERE leaf_membership.inhrelid = to_regclass(format('audit.%I', partition_name))
              AND parent_namespace.nspname = 'audit'
              AND parent.relname = parent_name
        ) THEN
            RAISE EXCEPTION 'derived audit partition name is occupied by a foreign relation'
                USING ERRCODE = '42P07';
        END IF;
    END LOOP;

    IF to_regprocedure('audit.provision_future_epoch_heads(date)') IS NOT NULL THEN
        EXECUTE 'SELECT audit.provision_future_epoch_heads($1)' USING target_month;
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION audit.provision_audit_month(date) FROM PUBLIC;

SELECT audit.provision_audit_month(
    date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
);
SELECT audit.provision_audit_month(
    (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + interval '1 month')::date
);
SELECT audit.provision_audit_month(
    (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + interval '2 months')::date
);
SELECT audit.provision_audit_month(
    (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + interval '3 months')::date
);
