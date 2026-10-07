package org.meldtech.migrationverify.adapter.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;
import org.meldtech.migrationverify.port.MigrationSqlParser;

public final class JSqlParserMigrationAdapter implements MigrationSqlParser {

    private static final String QUALIFIED_IDENTIFIER = "([a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*)";
    private static final Pattern PARTITION_TABLE =
            Pattern.compile(
                    "(?is)^CREATE\\s+TABLE\\s+" + QUALIFIED_IDENTIFIER + "\\s+PARTITION\\s+OF\\b");
    private static final Pattern PARTITIONED_TABLE =
            Pattern.compile(
                    "(?is)^CREATE\\s+TABLE\\s+"
                            + QUALIFIED_IDENTIFIER
                            + "\\s*\\(.*\\)\\s+PARTITION\\s+BY\\s+(?:LIST|RANGE|HASH)\\s*\\(");
    private static final Pattern QUALIFIED_CREATE_TABLE =
            Pattern.compile("(?is)^CREATE\\s+TABLE\\s+" + QUALIFIED_IDENTIFIER + "\\s*\\(");
    private static final Pattern FUNCTION =
            Pattern.compile(
                    "(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\s+" + QUALIFIED_IDENTIFIER);
    private static final Pattern TRIGGER =
            Pattern.compile("(?is)^CREATE\\s+TRIGGER\\b.*?\\s+ON\\s+" + QUALIFIED_IDENTIFIER);
    private static final Pattern POLICY =
            Pattern.compile(
                    "(?is)^CREATE\\s+POLICY\\s+[a-z][a-z0-9_]*\\s+ON\\s+" + QUALIFIED_IDENTIFIER);
    private static final Pattern ROW_LEVEL_SECURITY =
            Pattern.compile(
                    "(?is)^ALTER\\s+TABLE\\s+"
                            + QUALIFIED_IDENTIFIER
                            + "\\s+(?:ENABLE|FORCE)\\s+ROW\\s+LEVEL\\s+SECURITY$");
    private static final Pattern EXTENSION_SCHEMA =
            Pattern.compile(
                    "(?is)^CREATE\\s+EXTENSION\\b.*?\\s+WITH\\s+SCHEMA\\s+([a-z][a-z0-9_]*)");
    private static final Pattern PRIVILEGED_OBJECT =
            Pattern.compile("(?is)\\bON\\s+(?:TABLE|FUNCTION)\\s+" + QUALIFIED_IDENTIFIER);
    private static final Pattern PRIVILEGED_SCHEMA =
            Pattern.compile("(?is)\\bON\\s+SCHEMA\\s+([a-z][a-z0-9_]*)");
    private static final Pattern FUNCTION_INVOCATION =
            Pattern.compile("(?is)^SELECT\\s+" + QUALIFIED_IDENTIFIER + "\\s*\\(");
    private static final Pattern INDEX_COMMENT =
            Pattern.compile("(?is)^COMMENT\\s+ON\\s+INDEX\\s+" + QUALIFIED_IDENTIFIER);

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
        if (tokens(source).getFirst().equals("COPY")) {
            throw new IllegalArgumentException(
                    "DATA_MODIFICATION_FORBIDDEN: statement "
                            + ordinal
                            + " uses COPY; migration scripts must not modify rows");
        }
        Optional<ParsedMigrationStatement> postgresql = parsePostgresqlStatement(source, ordinal);
        if (postgresql.isPresent()) {
            return postgresql.get();
        }
        boolean concurrent = containsKeyword(source, "CONCURRENTLY");
        boolean newEmptyPartitionedParentIndex =
                source.contains("cbt:index-build NEW_EMPTY_PARTITIONED_PARENT");
        boolean notValid = containsKeywordSequence(source, "NOT", "VALID");
        boolean cascade = containsKeyword(source, "CASCADE");
        String parserSql = removePostgreSqlParserGaps(source);
        try {
            Statement statement = CCJSqlParserUtil.parse(parserSql);
            return translate(
                    statement,
                    ordinal,
                    concurrent,
                    newEmptyPartitionedParentIndex,
                    notValid,
                    cascade);
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException(
                    "Statement " + ordinal + " cannot be parsed as supported PostgreSQL DDL",
                    exception);
        }
    }

    private static Optional<ParsedMigrationStatement> parsePostgresqlStatement(
            String source, int ordinal) {
        String statement = stripLeadingComments(source);
        Matcher partition = PARTITION_TABLE.matcher(statement);
        if (partition.find()) {
            return Optional.of(
                    rawParsed(ordinal, StatementKind.CREATE_TABLE, partition.group(1), source));
        }
        Matcher partitionedTable = PARTITIONED_TABLE.matcher(statement);
        if (partitionedTable.find()) {
            return Optional.of(
                    rawParsed(
                            ordinal,
                            StatementKind.CREATE_TABLE,
                            partitionedTable.group(1),
                            source));
        }
        Matcher createTable = QUALIFIED_CREATE_TABLE.matcher(statement);
        if (createTable.find()) {
            return Optional.of(
                    rawParsed(ordinal, StatementKind.CREATE_TABLE, createTable.group(1), source));
        }
        Matcher function = FUNCTION.matcher(statement);
        if (function.find()) {
            return Optional.of(
                    rawParsed(ordinal, StatementKind.CREATE_FUNCTION, function.group(1), source));
        }
        Matcher trigger = TRIGGER.matcher(statement);
        if (trigger.find()) {
            return Optional.of(
                    rawParsed(ordinal, StatementKind.CREATE_TRIGGER, trigger.group(1), source));
        }
        Matcher policy = POLICY.matcher(statement);
        if (policy.find()) {
            return Optional.of(
                    rawParsed(ordinal, StatementKind.CREATE_POLICY, policy.group(1), source));
        }
        Matcher rowLevelSecurity = ROW_LEVEL_SECURITY.matcher(statement);
        if (rowLevelSecurity.find()) {
            return Optional.of(
                    rawParsed(
                            ordinal,
                            StatementKind.ROW_LEVEL_SECURITY,
                            rowLevelSecurity.group(1),
                            source));
        }
        Matcher extension = EXTENSION_SCHEMA.matcher(statement);
        if (extension.find()) {
            return Optional.of(
                    rawParsed(
                            ordinal,
                            StatementKind.CREATE_EXTENSION,
                            extension.group(1) + ".pgcrypto",
                            source));
        }
        if (statement.regionMatches(true, 0, "DO ", 0, 3)) {
            return Optional.of(rawParsed(ordinal, StatementKind.PROCEDURAL_BLOCK, "", source));
        }
        if (statement.regionMatches(true, 0, "GRANT ", 0, 6)) {
            return Optional.of(
                    rawParsed(ordinal, StatementKind.GRANT, privilegedRelation(statement), source));
        }
        if (statement.regionMatches(true, 0, "REVOKE ", 0, 7)) {
            return Optional.of(
                    rawParsed(
                            ordinal, StatementKind.REVOKE, privilegedRelation(statement), source));
        }
        Matcher invocation = FUNCTION_INVOCATION.matcher(statement);
        if (invocation.find()) {
            return Optional.of(
                    rawParsed(ordinal, StatementKind.INVOKE_FUNCTION, invocation.group(1), source));
        }
        Matcher indexComment = INDEX_COMMENT.matcher(statement);
        if (indexComment.find()) {
            return Optional.of(
                    rawParsed(ordinal, StatementKind.COMMENT, indexComment.group(1), source));
        }
        return Optional.empty();
    }

    private static String privilegedRelation(String statement) {
        Matcher object = PRIVILEGED_OBJECT.matcher(statement);
        if (object.find()) {
            return object.group(1);
        }
        Matcher schema = PRIVILEGED_SCHEMA.matcher(statement);
        return schema.find() ? schema.group(1) + ".__schema__" : "";
    }

    private static ParsedMigrationStatement rawParsed(
            int ordinal, StatementKind kind, String relation, String source) {
        String objectName =
                relation.isBlank() || !relation.contains(".")
                        ? relation
                        : relation.substring(relation.indexOf('.') + 1);
        return new ParsedMigrationStatement(
                ordinal,
                kind,
                relation.toLowerCase(Locale.ROOT),
                objectName,
                redactLiterals(source),
                false,
                false,
                false,
                false,
                false,
                false,
                1);
    }

    private static String stripLeadingComments(String source) {
        return source.replaceFirst("(?s)^(?:\\s*--[^\\r\\n]*(?:\\r?\\n|$))*\\s*", "");
    }

    private static ParsedMigrationStatement translate(
            Statement statement,
            int ordinal,
            boolean concurrent,
            boolean newEmptyPartitionedParentIndex,
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
                    newEmptyPartitionedParentIndex,
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
                    newEmptyPartitionedParentIndex,
                    notValid,
                    cascade,
                    false,
                    false,
                    1);
        }
        if (statement instanceof Alter alter) {
            return translateAlter(
                    alter, ordinal, concurrent, newEmptyPartitionedParentIndex, notValid, cascade);
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
                    drop.getName().getFullyQualifiedName(),
                    statement,
                    concurrent,
                    newEmptyPartitionedParentIndex,
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
                    newEmptyPartitionedParentIndex,
                    notValid,
                    cascade,
                    false,
                    false,
                    1);
        }
        Table modifiedTable = modifiedTable(statement);
        if (modifiedTable != null) {
            return parsed(
                    ordinal,
                    StatementKind.DATA_MODIFICATION,
                    relation(modifiedTable),
                    modifiedTable.getName(),
                    statement,
                    concurrent,
                    newEmptyPartitionedParentIndex,
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
                newEmptyPartitionedParentIndex,
                notValid,
                cascade,
                false,
                false,
                1);
    }

    private static Table modifiedTable(Statement statement) {
        return switch (statement) {
            case Insert insert -> insert.getTable();
            case Update update -> update.getTable();
            case Delete delete -> delete.getTable();
            case Merge merge -> merge.getTable();
            case Truncate truncate -> truncate.getTable();
            case Upsert upsert -> upsert.getTable();
            default -> null;
        };
    }

    private static ParsedMigrationStatement translateAlter(
            Alter alter,
            int ordinal,
            boolean concurrent,
            boolean newEmptyPartitionedParentIndex,
            boolean notValid,
            boolean cascade) {
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
                newEmptyPartitionedParentIndex,
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
            boolean newEmptyPartitionedParentIndex,
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
                newEmptyPartitionedParentIndex,
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
        return List.of(codeOnly(sql).toUpperCase(Locale.ROOT).split("[^A-Z_]+"));
    }

    private static String codeOnly(String sql) {
        var code = new StringBuilder(sql.length());
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarQuote = null;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (lineComment) {
                if (character == '\n') {
                    lineComment = false;
                    code.append(character);
                }
                continue;
            }
            if (blockComment) {
                if (character == '*' && index + 1 < sql.length() && sql.charAt(index + 1) == '/') {
                    index++;
                    blockComment = false;
                }
                continue;
            }
            if (dollarQuote != null) {
                if (sql.startsWith(dollarQuote, index)) {
                    index += dollarQuote.length() - 1;
                    dollarQuote = null;
                }
                continue;
            }
            if (singleQuoted) {
                if (character == '\''
                        && index + 1 < sql.length()
                        && sql.charAt(index + 1) == '\'') {
                    index++;
                } else if (character == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                code.append(character);
                if (character == '"' && index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
                    code.append('"');
                    index++;
                } else if (character == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (character == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index++;
                lineComment = true;
            } else if (character == '/'
                    && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '*') {
                index++;
                blockComment = true;
            } else if (character == '\'') {
                singleQuoted = true;
                code.append(' ');
            } else if (character == '"') {
                doubleQuoted = true;
                code.append(character);
            } else if (character == '$') {
                String candidate = dollarQuoteAt(sql, index);
                if (candidate != null) {
                    index += candidate.length() - 1;
                    dollarQuote = candidate;
                    code.append(' ');
                } else {
                    code.append(character);
                }
            } else {
                code.append(character);
            }
        }
        return code.toString();
    }

    private static List<String> splitStatements(String sql) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarQuote = null;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);

            if (lineComment) {
                current.append(character);
                if (character == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                current.append(character);
                if (character == '*' && index + 1 < sql.length() && sql.charAt(index + 1) == '/') {
                    current.append('/');
                    index++;
                    blockComment = false;
                }
                continue;
            }
            if (dollarQuote != null) {
                if (sql.startsWith(dollarQuote, index)) {
                    current.append(dollarQuote);
                    index += dollarQuote.length() - 1;
                    dollarQuote = null;
                } else {
                    current.append(character);
                }
                continue;
            }
            if (singleQuoted) {
                current.append(character);
                if (character == '\''
                        && index + 1 < sql.length()
                        && sql.charAt(index + 1) == '\'') {
                    current.append('\'');
                    index++;
                } else if (character == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                current.append(character);
                if (character == '"' && index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else if (character == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (character == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                current.append("--");
                index++;
                lineComment = true;
                continue;
            }
            if (character == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                current.append("/*");
                index++;
                blockComment = true;
                continue;
            }
            if (character == '\'') {
                current.append(character);
                singleQuoted = true;
                continue;
            }
            if (character == '"') {
                current.append(character);
                doubleQuoted = true;
                continue;
            }
            if (character == '$') {
                String candidate = dollarQuoteAt(sql, index);
                if (candidate != null) {
                    current.append(candidate);
                    index += candidate.length() - 1;
                    dollarQuote = candidate;
                    continue;
                }
            }
            if (character == ';') {
                addStatement(result, current);
            } else {
                current.append(character);
            }
        }
        addStatement(result, current);
        return List.copyOf(result);
    }

    private static String dollarQuoteAt(String sql, int offset) {
        int closing = sql.indexOf('$', offset + 1);
        if (closing < 0) {
            return null;
        }
        String tag = sql.substring(offset + 1, closing);
        return tag.matches("[A-Za-z_][A-Za-z0-9_]*|^$") ? sql.substring(offset, closing + 1) : null;
    }

    private static void addStatement(List<String> result, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            result.add(statement);
        }
        current.setLength(0);
    }
}
