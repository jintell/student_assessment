-- cbt:phase EXPAND
-- cbt:module outbox
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 create the principal before its RLS policy

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_txn_examentry') THEN
        CREATE ROLE app_txn_examentry;
    END IF;
END
$$;

ALTER ROLE app_txn_examentry WITH
    NOLOGIN
    NOINHERIT
    NOCREATEROLE;
