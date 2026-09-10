DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_delivery') THEN
        CREATE ROLE app_delivery;
    END IF;
END
$$;

ALTER ROLE app_delivery WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA delivery FROM PUBLIC;
GRANT USAGE ON SCHEMA delivery TO app_delivery;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA delivery TO app_delivery;
