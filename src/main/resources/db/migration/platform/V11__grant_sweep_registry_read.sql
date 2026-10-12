-- cbt:phase EXPAND
-- cbt:module platform
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-006 grant the background workload read-only sweep registry access

GRANT USAGE ON SCHEMA platform TO app_worker;
GRANT SELECT ON TABLE platform.sweep_registry TO app_worker;
