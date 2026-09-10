DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_examaccess') THEN
        CREATE ROLE app_examaccess;
    END IF;
END
$$;

ALTER ROLE app_examaccess WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA examaccess FROM PUBLIC;
GRANT USAGE ON SCHEMA examaccess TO app_examaccess;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA examaccess TO app_examaccess;
