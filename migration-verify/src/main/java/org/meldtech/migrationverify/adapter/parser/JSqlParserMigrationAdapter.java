package org.meldtech.migrationverify.adapter.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.comment.Comment;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;
import org.meldtech.migrationverify.port.MigrationSqlParser;

public final class JSqlParserMigrationAdapter implements MigrationSqlParser {

    @Override
    public List<ParsedMigrationStatement> parse(Path migration) {
        try {
            String sql = Files.readString(migration);
            String body = sql.lines().skip(4).reduce("", (left, right) -> left + right + "\n");
            List<String> sourceStatements = splitStatements(body);
            var parsedStatements = new ArrayList<ParsedMigrationStatement>();
            for (int index = 0; index < sourceStatements.size(); index++) {
                parsedStatements.add(parseStatement(sourceStatements.get(index), index + 1));
            }
            return List.copyOf(parsedStatements);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    migration + ": cannot read migration SQL: " + exception.getMessage(),
                    exception);
        }
    }

    private static ParsedMigrationStatement parseStatement(String source, int ordinal) {
        boolean concurrent = containsKeyword(source, "CONCURRENTLY");
        boolean notValid = containsKeywordSequence(source, "NOT", "VALID");
        boolean cascade = containsKeyword(source, "CASCADE");
        String parserSql = removePostgreSqlParserGaps(source);
        try {
            Statement statement = CCJSqlParserUtil.parse(parserSql);
            return translate(statement, ordinal, concurrent, notValid, cascade);
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException(
                    "Statement " + ordinal + " cannot be parsed as supported PostgreSQL DDL",
                    exception);
        }
    }

    private static ParsedMigrationStatement translate(
            Statement statement,
            int ordinal,
            boolean concurrent,
            boolean notValid,
            boolean cascade) {
        if (statement instanceof CreateTable createTable) {
            return parsed(
                    ordinal,
                    StatementKind.CREATE_TABLE,
                    relation(createTable.getTable()),
                    createTable.getTable().getName(),
                    statement,
                    concurrent,
                    notValid,
                    cascade,
                    hasUnsafeNotNull(createTable.getColumnDefinitions()),
                    false,
                    1);
        }
        if (statement instanceof CreateIndex createIndex) {
            return parsed(
                    ordinal,
                    StatementKind.CREATE_INDEX,
                    relation(createIndex.getTable()),
                    createIndex.getIndex().getName(),
                    statement,
                    concurrent,
                    notValid,
                    cascade,
                    false,
                    false,
                    1);
        }
        if (statement instanceof Alter alter) {
            return translateAlter(alter, ordinal, concurrent, notValid, cascade);
        }
        if (statement instanceof Drop drop) {
            StatementKind kind =
                    "TABLE".equalsIgnoreCase(drop.getType())
                            ? StatementKind.DROP_TABLE
                            : "INDEX".equalsIgnoreCase(drop.getType())
                                    ? StatementKind.DROP_INDEX
                                    : StatementKind.OTHER;
            return parsed(
                    ordinal,
                    kind,
                    relation(drop.getName()),
                    drop.getName().getName(),
                    statement,
                    concurrent,
                    notValid,
                    cascade,
                    false,
                    false,
                    1);
        }
        if (statement instanceof Comment comment) {
            Table table = comment.getTable();
            if (table == null && comment.getColumn() != null) {
                table = comment.getColumn().getTable();
            }
            return parsed(
                    ordinal,
                    StatementKind.COMMENT,
                    relation(table),
                    table == null ? "" : table.getName(),
                    statement,
                    concurrent,
                    notValid,
                    cascade,
                    false,
                    false,
                    1);
        }
        return parsed(
                ordinal,
                StatementKind.OTHER,
                "",
                "",
                statement,
                concurrent,
                notValid,
                cascade,
                false,
                false,
                1);
    }

    private static ParsedMigrationStatement translateAlter(
            Alter alter, int ordinal, boolean concurrent, boolean notValid, boolean cascade) {
        List<AlterExpression> expressions = alter.getAlterExpressions();
        StatementKind kind = StatementKind.OTHER;
        String objectName = "";
        boolean unsafeNotNull = false;
        if (expressions != null && expressions.size() == 1) {
            AlterExpression expression = expressions.getFirst();
            kind = alterKind(expression);
            objectName = alterObjectName(expression);
            unsafeNotNull = hasUnsafeNotNull(expression.getColDataTypeList());
        }
        return parsed(
                ordinal,
                kind,
                relation(alter.getTable()),
                objectName,
                alter,
                concurrent,
                notValid,
                cascade,
                unsafeNotNull,
                true,
                expressions == null ? 0 : expressions.size());
    }

    private static StatementKind alterKind(AlterExpression expression) {
        if (expression.getOperation() == AlterOperation.ADD) {
            return expression.getColDataTypeList() != null
                    ? StatementKind.ADD_COLUMN
                    : expression.getIndex() != null
                            ? StatementKind.ADD_CONSTRAINT
                            : StatementKind.OTHER;
        }
        if (expression.getOperation() == AlterOperation.DROP) {
            return expression.getConstraintName() != null
                    ? StatementKind.DROP_CONSTRAINT
                    : expression.getColumnName() != null
                            ? StatementKind.DROP_COLUMN
                            : StatementKind.OTHER;
        }
        if (expression.getOperation() == AlterOperation.ALTER) {
            if (expression.getColumnSetDefaultList() != null) {
                return StatementKind.SET_DEFAULT;
            }
            if (expression.getColumnDropDefaultList() != null) {
                return StatementKind.DROP_DEFAULT;
            }
        }
        if (expression.getOperation() == AlterOperation.RENAME
                && expression.getColumnOldName() != null) {
            return StatementKind.RENAME_COLUMN;
        }
        String optional = expression.getOptionalSpecifier();
        if (optional != null
                && optional.toUpperCase(Locale.ROOT).startsWith("VALIDATE CONSTRAINT")) {
            return StatementKind.VALIDATE_CONSTRAINT;
        }
        return StatementKind.OTHER;
    }

    private static String alterObjectName(AlterExpression expression) {
        if (expression.getColumnName() != null) {
            return expression.getColumnName();
        }
        if (expression.getConstraintName() != null) {
            return expression.getConstraintName();
        }
        if (expression.getIndex() != null && expression.getIndex().getName() != null) {
            return expression.getIndex().getName();
        }
        if (expression.getColDataTypeList() != null && !expression.getColDataTypeList().isEmpty()) {
            return expression.getColDataTypeList().getFirst().getColumnName();
        }
        return "";
    }

    private static ParsedMigrationStatement parsed(
            int ordinal,
            StatementKind kind,
            String relation,
            String objectName,
            Statement statement,
            boolean concurrent,
            boolean notValid,
            boolean cascade,
            boolean notNullWithoutDefault,
            boolean alterTable,
            int operationCount) {
        return new ParsedMigrationStatement(
                ordinal,
                kind,
                relation,
                objectName,
                redactLiterals(statement.toString()),
                concurrent,
                notValid,
                cascade,
                notNullWithoutDefault,
                alterTable,
                operationCount);
    }

    private static String redactLiterals(String sql) {
        var redacted = new StringBuilder(sql.length());
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (character == '\'') {
                redacted.append("'?'");
                index++;
                while (index < sql.length()) {
                    if (sql.charAt(index) == '\'') {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                            index += 2;
                            continue;
                        }
                        break;
                    }
                    index++;
                }
                continue;
            }
            boolean numericLiteral =
                    Character.isDigit(character)
                            && (index == 0
                                    || (!Character.isLetterOrDigit(sql.charAt(index - 1))
                                            && sql.charAt(index - 1) != '_'));
            if (numericLiteral) {
                redacted.append('?');
                while (index + 1 < sql.length()
                        && (Character.isDigit(sql.charAt(index + 1))
                                || sql.charAt(index + 1) == '.')) {
                    index++;
                }
            } else {
                redacted.append(character);
            }
        }
        return redacted.toString();
    }

    private static boolean hasUnsafeNotNull(List<? extends ColumnDefinition> columnDefinitions) {
        if (columnDefinitions == null) {
            return false;
        }
        return columnDefinitions.stream().anyMatch(JSqlParserMigrationAdapter::hasUnsafeNotNull);
    }

    private static boolean hasUnsafeNotNull(ColumnDefinition column) {
        List<String> specifications = column.getColumnSpecs();
        if (specifications == null) {
            return false;
        }
        List<String> upper =
                specifications.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
        for (int index = 0; index + 1 < upper.size(); index++) {
            if (upper.get(index).equals("NOT") && upper.get(index + 1).equals("NULL")) {
                return !upper.contains("DEFAULT");
            }
        }
        return false;
    }

    private static String relation(Table table) {
        return table == null ? "" : table.getFullyQualifiedName().toLowerCase(Locale.ROOT);
    }

    private static String removePostgreSqlParserGaps(String sql) {
        return sql.replaceFirst(
                        "(?i)^(\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX)\\s+CONCURRENTLY\\s+", "$1 ")
                .replaceFirst("(?i)^(\\s*DROP\\s+INDEX)\\s+CONCURRENTLY\\s+", "$1 ")
                .replaceFirst("(?i)\\s+NOT\\s+VALID\\s*$", "");
    }

    private static boolean containsKeyword(String sql, String keyword) {
        return tokens(sql).contains(keyword);
    }

    private static boolean containsKeywordSequence(String sql, String first, String second) {
        List<String> tokens = tokens(sql);
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals(first) && tokens.get(index + 1).equals(second)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokens(String sql) {
        return List.of(sql.toUpperCase(Locale.ROOT).split("[^A-Z_]+"));
    }

    private static List<String> splitStatements(String sql) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (character == '\'' && (index == 0 || sql.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            }
            if (character == ';' && !quoted) {
                addStatement(result, current);
            } else {
                current.append(character);
            }
        }
        addStatement(result, current);
        return List.copyOf(result);
    }

    private static void addStatement(List<String> result, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            result.add(statement);
        }
        current.setLength(0);
    }
}
