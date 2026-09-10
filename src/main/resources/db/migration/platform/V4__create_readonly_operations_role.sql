DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_readonly_ops') THEN
        CREATE ROLE app_readonly_ops;
    END IF;
END
$$;

ALTER ROLE app_readonly_ops WITH LOGIN NOINHERIT;

CREATE VIEW platform.database_diagnostics
WITH (security_barrier = true, security_invoker = true)
AS
SELECT
    numbackends,
    xact_commit,
    xact_rollback,
    blks_read,
    blks_hit,
    deadlocks
FROM pg_catalog.pg_stat_database
WHERE datname = current_database();

COMMENT ON VIEW platform.database_diagnostics IS
    'Non-PII operational counters intended for read-replica diagnostics';

GRANT USAGE ON SCHEMA platform TO app_readonly_ops;
GRANT SELECT ON platform.database_diagnostics TO app_readonly_ops;
