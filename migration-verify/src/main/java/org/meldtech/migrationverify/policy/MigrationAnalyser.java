package org.meldtech.migrationverify.policy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.meldtech.migrationverify.core.MigrationAnalysis;
import org.meldtech.migrationverify.port.MigrationSqlParser;

public final class MigrationAnalyser {

    private final MigrationHeaderParser headerParser;
    private final MigrationSqlParser sqlParser;
    private final ClosedDdlAllowlist allowlist;
    private final ForbiddenOperationPolicy forbiddenOperations;
    private final OwnSchemaCheck ownSchemaCheck;

    public MigrationAnalyser(
            MigrationHeaderParser headerParser,
            MigrationSqlParser sqlParser,
            ClosedDdlAllowlist allowlist) {
        this(
                headerParser,
                sqlParser,
                allowlist,
                new ForbiddenOperationPolicy(List.of()),
                new OwnSchemaCheck());
    }

    public MigrationAnalyser(
            MigrationHeaderParser headerParser,
            MigrationSqlParser sqlParser,
            ClosedDdlAllowlist allowlist,
            ForbiddenOperationPolicy forbiddenOperations) {
        this(headerParser, sqlParser, allowlist, forbiddenOperations, new OwnSchemaCheck());
    }

    public MigrationAnalyser(
            MigrationHeaderParser headerParser,
            MigrationSqlParser sqlParser,
            ClosedDdlAllowlist allowlist,
            ForbiddenOperationPolicy forbiddenOperations,
            OwnSchemaCheck ownSchemaCheck) {
        this.headerParser = headerParser;
        this.sqlParser = sqlParser;
        this.allowlist = allowlist;
        this.forbiddenOperations = forbiddenOperations;
        this.ownSchemaCheck = ownSchemaCheck;
    }

    public MigrationAnalysis analyse(Path migration) {
        var header = headerParser.parse(migration);
        var statements = sqlParser.parse(migration);
        var violations = new ArrayList<>(allowlist.evaluate(header, statements));
        violations.addAll(forbiddenOperations.evaluate(header, statements));
        violations.addAll(ownSchemaCheck.evaluate(header, statements));
        return new MigrationAnalysis(header, statements, List.copyOf(violations));
    }
}
