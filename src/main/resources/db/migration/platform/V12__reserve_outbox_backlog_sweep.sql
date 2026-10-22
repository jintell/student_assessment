-- cbt:phase EXPAND
-- cbt:module platform
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 reserve disabled backlog collection pending owner cadence and deployment review

INSERT INTO platform.sweep_registry (
    sweep_name, lock_key, owning_module, owning_feature,
    default_interval_ms, enabled, system_actor
)
VALUES ('outbox-backlog', 722004001, 'outbox', 'FEAT-PLAT-004',
        60000, false, 'OUTBOX_RELAY');
