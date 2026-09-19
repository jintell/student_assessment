package org.meldtech.migrationverify.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationViolation;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;
import org.meldtech.migrationverify.core.StatementKind;

public final class OwnSchemaCheck {

    public List<MigrationViolation> evaluate(
            MigrationHeader header, List<ParsedMigrationStatement> statements) {
        var violations = new ArrayList<MigrationViolation>();
        for (ParsedMigrationStatement statement : statements) {
            if (!statement.relation().isBlank()) {
                String relationSchema = schema(statement.relation()).orElse("<unqualified>");
                if (!relationSchema.equals(header.module())) {
                    violations.add(violation(header, statement, relationSchema, "relation"));
                }
            }
            if (statement.kind() == StatementKind.CREATE_INDEX
                    || statement.kind() == StatementKind.DROP_INDEX) {
                String indexSchema = schema(statement.objectName()).orElse("<unqualified>");
                if (!indexSchema.equals(header.module())) {
                    violations.add(violation(header, statement, indexSchema, "index"));
                }
            }
        }
        return List.copyOf(violations);
    }

    private static Optional<String> schema(String identifier) {
        int separator = identifier.indexOf('.');
        return separator < 1 ? Optional.empty() : Optional.of(identifier.substring(0, separator));
    }

    private static MigrationViolation violation(
            MigrationHeader header,
            ParsedMigrationStatement statement,
            String actualSchema,
            String objectType) {
        return new MigrationViolation(
                "FOREIGN_SCHEMA_ACCESS",
                statement.ordinal(),
                header.source()
                        + ": module '"
                        + header.module()
                        + "' cannot modify "
                        + objectType
                        + " in foreign schema '"
                        + actualSchema
                        + "'");
    }
}
