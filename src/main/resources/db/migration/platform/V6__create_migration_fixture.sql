-- cbt:phase EXPAND
-- cbt:module platform
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-005 add the migration conformance fixture

CREATE TABLE platform.migration_fixture (
    fixture_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nullable_value text,
    constant_default_value text DEFAULT 'baseline',
    constraint_candidate integer,
    index_candidate varchar(64),
    obsolete_value text,
    referenced_fixture_id bigint
);

COMMENT ON TABLE platform.migration_fixture IS
    'Synthetic migration-conformance artifact; not a business table and must contain no personal data';
COMMENT ON COLUMN platform.migration_fixture.nullable_value IS
    'Synthetic nullable value used only by migration verification';
COMMENT ON COLUMN platform.migration_fixture.constant_default_value IS
    'Synthetic constant-default value used only by migration verification';
COMMENT ON COLUMN platform.migration_fixture.constraint_candidate IS
    'Synthetic value used to exercise constraint migration shapes';
COMMENT ON COLUMN platform.migration_fixture.index_candidate IS
    'Synthetic value used to exercise concurrent index migration shapes';
COMMENT ON COLUMN platform.migration_fixture.obsolete_value IS
    'Synthetic value used to exercise contract migration shapes';
COMMENT ON COLUMN platform.migration_fixture.referenced_fixture_id IS
    'Synthetic value used to exercise reference migration shapes';
