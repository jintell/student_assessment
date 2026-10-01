-- cbt:phase EXPAND
-- cbt:module outbox
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 deny direct runtime access to outbox partitions

CREATE FUNCTION outbox.lock_down_outbox_partition(partition_relation regclass)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_inherits
        WHERE inhrelid = partition_relation
          AND inhparent = 'outbox.outbox_event'::regclass
    ) THEN
        RAISE EXCEPTION 'relation is not an outbox.outbox_event partition'
            USING ERRCODE = '22023';
    END IF;

    EXECUTE format(
        'REVOKE ALL PRIVILEGES ON TABLE %s FROM '
        || 'app_tenancy, app_iam, app_academic, app_people, '
        || 'app_questionbank, app_authoring, app_examaccess, app_delivery, '
        || 'app_grading, app_result, app_correction, app_notification, '
        || 'app_txn_examentry',
        partition_relation
    );
END;
$$;

REVOKE ALL ON FUNCTION outbox.lock_down_outbox_partition(regclass) FROM PUBLIC;

SELECT outbox.lock_down_outbox_partition(inhrelid::regclass)
FROM pg_catalog.pg_inherits
WHERE inhparent = 'outbox.outbox_event'::regclass;

CREATE OR REPLACE FUNCTION outbox.attach_week_partition(partition_start date)
RETURNS name
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    partition_end date;
    partition_name name;
    partition_relation regclass;
    attached_to_outbox boolean;
BEGIN
    IF partition_start IS NULL OR extract(isodow FROM partition_start) <> 1 THEN
        RAISE EXCEPTION 'partition_start must be a UTC-aligned Monday'
            USING ERRCODE = '22023';
    END IF;

    partition_end := partition_start + 7;
    partition_name := ('outbox_event_' || to_char(partition_start, 'YYYYMMDD'))::name;
    PERFORM pg_advisory_xact_lock(768528580130648838);

    partition_relation := to_regclass(format('outbox.%I', partition_name));
    IF partition_relation IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_inherits
            WHERE inhrelid = partition_relation
              AND inhparent = 'outbox.outbox_event'::regclass
        ) INTO attached_to_outbox;
        IF NOT attached_to_outbox THEN
            RAISE EXCEPTION 'derived partition name is occupied by a foreign relation'
                USING ERRCODE = '42P07';
        END IF;
        PERFORM outbox.lock_down_outbox_partition(partition_relation);
        RETURN partition_name;
    END IF;

    EXECUTE format(
        'CREATE TABLE outbox.%I PARTITION OF outbox.outbox_event '
        || 'FOR VALUES FROM (%L) TO (%L)',
        partition_name,
        make_timestamptz(
            extract(year FROM partition_start)::integer,
            extract(month FROM partition_start)::integer,
            extract(day FROM partition_start)::integer,
            0,
            0,
            0,
            'UTC'
        ),
        make_timestamptz(
            extract(year FROM partition_end)::integer,
            extract(month FROM partition_end)::integer,
            extract(day FROM partition_end)::integer,
            0,
            0,
            0,
            'UTC'
        )
    );

    partition_relation := to_regclass(format('outbox.%I', partition_name));
    PERFORM outbox.lock_down_outbox_partition(partition_relation);
    RETURN partition_name;
END;
$$;
