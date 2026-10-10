package org.meldtech.migrationverify.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meldtech.migrationverify.core.MigrationAnalysis;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationPhase;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;

class TouchedRelationCollectorTest {

    @Test
    void collectsOnlyPreExistingTablesThatNeedPreviousReleaseReadWriteCoverage() {
        var auditMigration =
                analysis(
                        statement(StatementKind.CREATE_TABLE, "audit.audit_event", "CREATE TABLE"),
                        statement(
                                StatementKind.CREATE_INDEX,
                                "audit.audit_event",
                                "CREATE INDEX ON audit.audit_event"),
                        statement(
                                StatementKind.CREATE_FUNCTION,
                                "audit.append_event",
                                "CREATE FUNCTION audit.append_event"),
                        statement(
                                StatementKind.GRANT,
                                "audit.__schema__",
                                "GRANT USAGE ON SCHEMA audit"),
                        statement(
                                StatementKind.REVOKE,
                                "audit.append_event",
                                "REVOKE EXECUTE ON FUNCTION audit.append_event"));
        var existingTableMigration =
                analysis(
                        alterStatement(
                                StatementKind.ADD_COLUMN,
                                "platform.migration_fixture",
                                "ALTER TABLE platform.migration_fixture ADD COLUMN expanded text"));

        Set<String> relations =
                new TouchedRelationCollector()
                        .collect(List.of(auditMigration, existingTableMigration));

        assertEquals(Set.of("platform.migration_fixture"), relations);
    }

    @Test
    void includesTablePrivilegesAndIndexesOnPreExistingTables() {
        var analysis =
                analysis(
                        statement(
                                StatementKind.GRANT,
                                "delivery.answer",
                                "GRANT SELECT ON TABLE delivery.answer TO app_delivery"),
                        statement(
                                StatementKind.CREATE_INDEX,
                                "delivery.attempt",
                                "CREATE INDEX ON delivery.attempt"));

        Set<String> relations = new TouchedRelationCollector().collect(List.of(analysis));

        assertEquals(Set.of("delivery.answer", "delivery.attempt"), relations);
    }

    private static MigrationAnalysis analysis(ParsedMigrationStatement... statements) {
        var header =
                new MigrationHeader(
                        Path.of("migration.sql"), MigrationPhase.EXPAND, "audit", true, "test");
        return new MigrationAnalysis(header, List.of(statements), List.of());
    }

    private static ParsedMigrationStatement statement(
            StatementKind kind, String relation, String normalizedForm) {
        return statement(kind, relation, normalizedForm, false);
    }

    private static ParsedMigrationStatement alterStatement(
            StatementKind kind, String relation, String normalizedForm) {
        return statement(kind, relation, normalizedForm, true);
    }

    private static ParsedMigrationStatement statement(
            StatementKind kind, String relation, String normalizedForm, boolean alterTable) {
        return new ParsedMigrationStatement(
                1,
                kind,
                relation,
                "object",
                normalizedForm,
                false,
                false,
                false,
                false,
                false,
                alterTable,
                1);
    }
}
