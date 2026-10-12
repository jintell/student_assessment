-- cbt:phase EXPAND
-- cbt:module platform
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-006 reserve six disabled scheduler identities from the approved registry design

-- Unspecified cadences use the P2.3 placeholder and require owner review before enablement.
-- The enabled reference sweep belongs exclusively to P4.26 test fixtures.
INSERT INTO platform.sweep_registry (
    sweep_name, lock_key, owning_module, owning_feature,
    default_interval_ms, enabled, system_actor
)
VALUES
    ('auto-submit', 8663753269692232313, 'delivery', 'FEAT-DLV-004',
     10000, false, 'AUTO_SUBMIT_SWEEPER'),
    ('stale-claim-reclaim', -6495990722266513791, 'grading', 'FEAT-GRD-002',
     30000, false, 'GRADING_WORKER'),
    ('pin-material-destruction', -2916676166989647429, 'examaccess', 'FEAT-EXAM-002',
     60000, false, 'RETENTION_ENGINE'),
    ('retention', -4505843845379211855, 'audit', 'FEAT-PRIV-001',
     60000, false, 'RETENTION_ENGINE'),
    ('idp-reconciliation', -4891396382355818010, 'iam', 'FEAT-IAM-005',
     60000, false, 'IDP_RECONCILER'),
    ('outbox-relay', -2484966499978243116, 'outbox', 'FEAT-PLAT-004',
     200, false, 'OUTBOX_RELAY')
ON CONFLICT (sweep_name) DO NOTHING;
