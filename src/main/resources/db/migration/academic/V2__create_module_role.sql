DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_academic') THEN
        CREATE ROLE app_academic;
    END IF;
END
$$;

ALTER ROLE app_academic WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA academic FROM PUBLIC;
GRANT USAGE ON SCHEMA academic TO app_academic;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA academic TO app_academic;
