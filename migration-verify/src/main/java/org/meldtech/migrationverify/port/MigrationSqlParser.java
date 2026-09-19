package org.meldtech.migrationverify.port;

import java.nio.file.Path;
import java.util.List;
import org.meldtech.migrationverify.core.ParsedMigrationStatement;

public interface MigrationSqlParser {

    List<ParsedMigrationStatement> parse(Path migration);
}
