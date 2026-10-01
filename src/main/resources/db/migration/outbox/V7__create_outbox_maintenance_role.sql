-- cbt:phase EXPAND
-- cbt:module outbox
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 create guarded partition maintenance authority

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_outbox_maintenance'
    ) THEN
        CREATE ROLE app_outbox_maintenance;
    END IF;
END
$$;

ALTER ROLE app_outbox_maintenance WITH
    NOLOGIN
    NOINHERIT
    NOCREATEROLE;

GRANT USAGE ON SCHEMA outbox TO app_outbox_maintenance;
REVOKE ALL ON ALL TABLES IN SCHEMA outbox FROM app_outbox_maintenance;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_worker') THEN
        GRANT app_outbox_maintenance TO app_worker WITH INHERIT FALSE, SET TRUE;
    END IF;
END
$$;
