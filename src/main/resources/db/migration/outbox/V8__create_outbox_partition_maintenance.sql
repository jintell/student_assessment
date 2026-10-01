-- cbt:phase EXPAND
-- cbt:module outbox
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 provision and safely detach weekly partitions

CREATE FUNCTION outbox.attach_week_partition(partition_start date)
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

    RETURN partition_name;
END;
$$;

CREATE FUNCTION outbox.detach_expired_partition(partition_name name)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    partition_relation regclass;
    partition_start date;
    partition_end timestamp with time zone;
    has_ineligible_rows boolean;
BEGIN
    IF partition_name::text !~ '^outbox_event_[0-9]{8}$' THEN
        RAISE EXCEPTION 'partition_name is not an outbox weekly partition'
            USING ERRCODE = '22023';
    END IF;

    partition_start := to_date(substring(partition_name::text FROM 14), 'YYYYMMDD');
    IF to_char(partition_start, 'YYYYMMDD') <> substring(partition_name::text FROM 14)
            OR extract(isodow FROM partition_start) <> 1 THEN
        RAISE EXCEPTION 'partition_name does not encode a UTC-aligned Monday'
            USING ERRCODE = '22023';
    END IF;
    partition_end := make_timestamptz(
        extract(year FROM partition_start + 7)::integer,
        extract(month FROM partition_start + 7)::integer,
        extract(day FROM partition_start + 7)::integer,
        0,
        0,
        0,
        'UTC'
    );

    PERFORM pg_advisory_xact_lock(768528580130648838);
    partition_relation := to_regclass(format('outbox.%I', partition_name));
    IF partition_relation IS NULL OR NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_inherits
        WHERE inhrelid = partition_relation
          AND inhparent = 'outbox.outbox_event'::regclass
    ) THEN
        RAISE EXCEPTION 'relation is not attached to outbox.outbox_event'
            USING ERRCODE = '22023';
    END IF;

    IF partition_end > CURRENT_TIMESTAMP - interval '7 days' THEN
        RETURN false;
    END IF;

    EXECUTE format(
        'SELECT EXISTS ('
        || 'SELECT 1 FROM outbox.%I '
        || 'WHERE state <> ''PUBLISHED'' '
        || 'OR published_at IS NULL '
        || 'OR published_at > CURRENT_TIMESTAMP - interval ''7 days'''
        || ')',
        partition_name
    ) INTO has_ineligible_rows;

    IF has_ineligible_rows THEN
        RETURN false;
    END IF;

    EXECUTE format(
        'ALTER TABLE outbox.outbox_event DETACH PARTITION outbox.%I',
        partition_name
    );
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION outbox.attach_week_partition(date) FROM PUBLIC;
REVOKE ALL ON FUNCTION outbox.detach_expired_partition(name) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION outbox.attach_week_partition(date) TO app_outbox_maintenance;
GRANT EXECUTE ON FUNCTION outbox.detach_expired_partition(name) TO app_outbox_maintenance;

SELECT outbox.attach_week_partition(
    date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
);
SELECT outbox.attach_week_partition(
    date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + 7
);
SELECT outbox.attach_week_partition(
    date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + 14
);
