package org.meldtech.migrationverify.adapter.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.statement.insert.ConflictActionType;
import net.sf.jsqlparser.statement.insert.Insert;

final class ApprovedSweepRegistrySeed {

    private static final List<String> COLUMNS =
            List.of(
                    "sweep_name",
                    "lock_key",
                    "owning_module",
                    "owning_feature",
                    "default_interval_ms",
                    "enabled",
                    "system_actor");
    private static final List<SweepSeedRow> ROWS =
            List.of(
                    new SweepSeedRow(
                            "auto-submit",
                            8663753269692232313L,
                            "delivery",
                            "FEAT-DLV-004",
                            10_000L,
                            "AUTO_SUBMIT_SWEEPER"),
                    new SweepSeedRow(
                            "stale-claim-reclaim",
                            -6495990722266513791L,
                            "grading",
                            "FEAT-GRD-002",
                            30_000L,
                            "GRADING_WORKER"),
                    new SweepSeedRow(
                            "pin-material-destruction",
                            -2916676166989647429L,
                            "examaccess",
                            "FEAT-EXAM-002",
                            60_000L,
                            "RETENTION_ENGINE"),
                    new SweepSeedRow(
                            "retention",
                            -4505843845379211855L,
                            "audit",
                            "FEAT-PRIV-001",
                            60_000L,
                            "RETENTION_ENGINE"),
                    new SweepSeedRow(
                            "idp-reconciliation",
                            -4891396382355818010L,
                            "iam",
                            "FEAT-IAM-005",
                            60_000L,
                            "IDP_RECONCILER"),
                    new SweepSeedRow(
                            "outbox-relay",
                            -2484966499978243116L,
                            "outbox",
                            "FEAT-PLAT-004",
                            200L,
                            "OUTBOX_RELAY"));

    private ApprovedSweepRegistrySeed() {}

    static boolean matches(Insert insert) {
        if (!insert.getTable().getFullyQualifiedName().equals("platform.sweep_registry")
                || insert.getColumns() == null
                || !insert.getColumns().stream()
                        .map(column -> column.getUnquotedColumnName().toLowerCase(Locale.ROOT))
                        .toList()
                        .equals(COLUMNS)
                || insert.getValues() == null
                || insert.getReturningClause() != null
                || insert.getWithItemsList() != null
                || insert.isModifierIgnore()
                || insert.isOverwrite()
                || insert.isOnlyDefaultValues()
                || insert.getDuplicateUpdateSets() != null
                || insert.getSetUpdateSets() != null
                || !hasDoNothingConflict(insert)) {
            return false;
        }
        List<?> expressions = insert.getValues().getExpressions();
        if (expressions.size() != ROWS.size()) {
            return false;
        }
        var rows = new ArrayList<SweepSeedRow>();
        for (Object expression : expressions) {
            if (!(expression instanceof ParenthesedExpressionList<?> values)
                    || values.size() != COLUMNS.size()) {
                return false;
            }
            SweepSeedRow row = row(values);
            if (row == null) {
                return false;
            }
            rows.add(row);
        }
        return rows.equals(ROWS);
    }

    private static boolean hasDoNothingConflict(Insert insert) {
        var target = insert.getConflictTarget();
        var action = insert.getConflictAction();
        return target != null
                && target.getIndexColumnNames().equals(List.of("sweep_name"))
                && target.getIndexExpression() == null
                && target.getWhereExpression() == null
                && target.getConstraintName() == null
                && action != null
                && action.getConflictActionType() == ConflictActionType.DO_NOTHING
                && action.getUpdateSets() == null
                && action.getWhereExpression() == null;
    }

    private static SweepSeedRow row(ParenthesedExpressionList<?> values) {
        String sweepName = stringValue(values.get(0));
        Long lockKey = longValue(values.get(1));
        String owningModule = stringValue(values.get(2));
        String owningFeature = stringValue(values.get(3));
        Long interval = longValue(values.get(4));
        boolean disabled = values.get(5) instanceof BooleanValue value && !value.getValue();
        String actor = stringValue(values.get(6));
        if (sweepName == null
                || lockKey == null
                || owningModule == null
                || owningFeature == null
                || interval == null
                || !disabled
                || actor == null) {
            return null;
        }
        return new SweepSeedRow(sweepName, lockKey, owningModule, owningFeature, interval, actor);
    }

    private static String stringValue(Object expression) {
        return expression instanceof StringValue value ? value.getValue() : null;
    }

    private static Long longValue(Object expression) {
        if (expression instanceof LongValue value) {
            return value.getValue();
        }
        if (expression instanceof SignedExpression signed
                && signed.getSign() == '-'
                && signed.getExpression() instanceof LongValue value) {
            return -value.getValue();
        }
        return null;
    }

    private record SweepSeedRow(
            String sweepName,
            long lockKey,
            String owningModule,
            String owningFeature,
            long interval,
            String actor) {}
}
