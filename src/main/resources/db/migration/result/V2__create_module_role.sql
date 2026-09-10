DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_result') THEN
        CREATE ROLE app_result;
    END IF;
END
$$;

ALTER ROLE app_result WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA result FROM PUBLIC;
GRANT USAGE ON SCHEMA result TO app_result;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA result TO app_result;
