DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_questionbank') THEN
        CREATE ROLE app_questionbank;
    END IF;
END
$$;

ALTER ROLE app_questionbank WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA questionbank FROM PUBLIC;
GRANT USAGE ON SCHEMA questionbank TO app_questionbank;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA questionbank TO app_questionbank;
