DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_txn_examentry') THEN
        CREATE ROLE app_txn_examentry;
    END IF;
END
$$;

ALTER ROLE app_txn_examentry WITH NOLOGIN NOINHERIT;

GRANT USAGE ON SCHEMA examaccess, delivery, people, authoring, tenancy, audit, outbox
    TO app_txn_examentry;

ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA audit
    GRANT INSERT ON TABLES TO app_txn_examentry;
ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA outbox
    GRANT INSERT ON TABLES TO app_txn_examentry;
