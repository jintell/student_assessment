DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_notification') THEN
        CREATE ROLE app_notification;
    END IF;
END
$$;

ALTER ROLE app_notification WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA notification FROM PUBLIC;
GRANT USAGE ON SCHEMA notification TO app_notification;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA notification TO app_notification;
