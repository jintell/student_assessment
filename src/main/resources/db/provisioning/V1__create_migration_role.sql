DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_migrator') THEN
        CREATE ROLE app_migrator;
    END IF;
END
$$;

ALTER ROLE app_migrator WITH LOGIN NOINHERIT NOSUPERUSER NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS;

DO $$
BEGIN
    EXECUTE format('GRANT CREATE ON DATABASE %I TO app_migrator', current_database());
END
$$;
