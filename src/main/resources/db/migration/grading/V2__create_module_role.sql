DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_grading') THEN
        CREATE ROLE app_grading;
    END IF;
END
$$;

ALTER ROLE app_grading WITH NOLOGIN NOINHERIT;
REVOKE ALL ON SCHEMA grading FROM PUBLIC;
GRANT USAGE ON SCHEMA grading TO app_grading;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA grading TO app_grading;
