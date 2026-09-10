DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_tenancy') THEN
        CREATE ROLE app_tenancy;
    END IF;
END
$$;

ALTER ROLE app_tenancy WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA tenancy FROM PUBLIC;
GRANT USAGE ON SCHEMA tenancy TO app_tenancy;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tenancy TO app_tenancy;
