DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_people') THEN
        CREATE ROLE app_people;
    END IF;
END
$$;

ALTER ROLE app_people WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA people FROM PUBLIC;
GRANT USAGE ON SCHEMA people TO app_people;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA people TO app_people;
