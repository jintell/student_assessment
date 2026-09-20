-- cbt:phase EXPAND
-- cbt:module platform
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-005 exercise N-1 compatibility against an additive schema change

ALTER TABLE platform.migration_fixture
    ADD COLUMN expanded_value text;

COMMENT ON COLUMN platform.migration_fixture.expanded_value IS
    'Synthetic additive value used only by migration verification';
