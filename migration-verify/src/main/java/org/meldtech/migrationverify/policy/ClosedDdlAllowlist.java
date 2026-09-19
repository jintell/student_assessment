package org.meldtech.migrationverify.policy;

import java.util.*;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationPhase;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;

public final class ClosedDdlAllowlist {

    private static final Map<MigrationPhase, Set<StatementKind>> ALLOWED = allowedShapes();

    public List<MigrationViolation> evaluate(
            MigrationHeader header, List<ParsedMigrationStatement> statements) {
        var violations = new ArrayList<MigrationViolation>();
        for (int index = 0; index < statements.size(); index++) {
            ParsedMigrationStatement statement = statements.get(index);
            if (!ALLOWED.get(header.phase()).contains(statement.kind())) {
                violations.add(rejected(header, statement, "DDL_SHAPE_NOT_ALLOWED"));
                continue;
            }
            if (statement.operationCount() != 1) {
                violations.add(rejected(header, statement, "MULTIPLE_ALTER_OPERATIONS"));
            }
            if (statement.kind() == StatementKind.ADD_CONSTRAINT && !statement.notValid()) {
                violations.add(rejected(header, statement, "CONSTRAINT_MUST_BE_NOT_VALID"));
            }
            boolean concurrentShape =
                    statement.kind() == StatementKind.CREATE_INDEX
                            || statement.kind() == StatementKind.DROP_INDEX;
            if (concurrentShape && (!statement.concurrent() || header.transactional())) {
                violations.add(rejected(header, statement, "CONCURRENT_INDEX_REQUIRED"));
            }
            if (!concurrentShape && !header.transactional()) {
                violations.add(rejected(header, statement, "TRANSACTIONAL_SCRIPT_REQUIRED"));
            }
            if (statement.cascade()) {
                violations.add(rejected(header, statement, "CASCADE_NOT_ALLOWED"));
            }
            if (statement.kind() == StatementKind.COMMENT && !isAllowedComment(index, statements)) {
                violations.add(rejected(header, statement, "ORPHAN_COMMENT"));
            }
        }
        return List.copyOf(violations);
    }

    private static boolean isAllowedComment(int index, List<ParsedMigrationStatement> statements) {
        if (index == 0) {
            return false;
        }
        ParsedMigrationStatement comment = statements.get(index);
        for (int previousIndex = index - 1; previousIndex >= 0; previousIndex--) {
            ParsedMigrationStatement previous = statements.get(previousIndex);
            if (!previous.relation().equals(comment.relation())) {
                return false;
            }
            if (previous.kind() != StatementKind.COMMENT) {
                return EnumSet.of(
                                StatementKind.CREATE_TABLE,
                                StatementKind.ADD_COLUMN,
                                StatementKind.ADD_CONSTRAINT)
                        .contains(previous.kind());
            }
        }
        return false;
    }

    private static MigrationViolation rejected(
            MigrationHeader header, ParsedMigrationStatement statement, String code) {
        return new MigrationViolation(
                code,
                statement.ordinal(),
                header.source()
                        + ": statement "
                        + statement.ordinal()
                        + " rejected for "
                        + header.phase()
                        + " as parsed: '"
                        + statement.normalizedForm()
                        + "'");
    }

    private static Map<MigrationPhase, Set<StatementKind>> allowedShapes() {
        var allowed = new EnumMap<MigrationPhase, Set<StatementKind>>(MigrationPhase.class);
        allowed.put(
                MigrationPhase.EXPAND,
                EnumSet.of(
                        StatementKind.CREATE_TABLE,
                        StatementKind.ADD_COLUMN,
                        StatementKind.ADD_CONSTRAINT,
                        StatementKind.CREATE_INDEX,
                        StatementKind.COMMENT));
        allowed.put(
                MigrationPhase.MIGRATE,
                EnumSet.of(StatementKind.VALIDATE_CONSTRAINT, StatementKind.SET_DEFAULT));
        allowed.put(
                MigrationPhase.CONTRACT,
                EnumSet.of(
                        StatementKind.DROP_COLUMN,
                        StatementKind.DROP_CONSTRAINT,
                        StatementKind.DROP_DEFAULT,
                        StatementKind.DROP_INDEX,
                        StatementKind.DROP_TABLE));
        return Map.copyOf(allowed);
    }
}
