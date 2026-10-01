-- cbt:phase EXPAND
-- cbt:module outbox
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 create the least-privilege outbox relay role

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_outbox_relay') THEN
        CREATE ROLE app_outbox_relay;
    END IF;
END
$$;

ALTER ROLE app_outbox_relay WITH
    NOLOGIN
    NOINHERIT
    NOCREATEROLE;

GRANT USAGE ON SCHEMA outbox TO app_outbox_relay;
GRANT SELECT, UPDATE ON TABLE outbox.outbox_event TO app_outbox_relay;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_worker') THEN
        GRANT app_outbox_relay TO app_worker WITH INHERIT FALSE, SET TRUE;
    END IF;
END
$$;
