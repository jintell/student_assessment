DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_authoring') THEN
        CREATE ROLE app_authoring;
    END IF;
END
$$;

ALTER ROLE app_authoring WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA authoring FROM PUBLIC;
GRANT USAGE ON SCHEMA authoring TO app_authoring;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA authoring TO app_authoring;
