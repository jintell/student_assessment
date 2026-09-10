DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_correction') THEN
        CREATE ROLE app_correction;
    END IF;
END
$$;

ALTER ROLE app_correction WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA correction FROM PUBLIC;
GRANT USAGE ON SCHEMA correction TO app_correction;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA correction TO app_correction;
