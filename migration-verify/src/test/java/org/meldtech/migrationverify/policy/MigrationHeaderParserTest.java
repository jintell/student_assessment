package org.meldtech.migrationverify.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.migrationverify.core.MigrationPhase;

class MigrationHeaderParserTest {

    @TempDir Path directory;

    private final MigrationHeaderParser parser = new MigrationHeaderParser();

    @Test
    void parsesTheExactDirectiveSet() throws IOException {
        Path migration =
                migration(
                        "-- cbt:phase EXPAND\n"
                                + "-- cbt:module delivery\n"
                                + "-- cbt:transactional false\n"
                                + "-- cbt:justification FEAT-DLV-002 add lookup index\n"
                                + "CREATE INDEX CONCURRENTLY delivery_answer_idx ON delivery.answer(id);\n");

        var header = parser.parse(migration);

        assertEquals(MigrationPhase.EXPAND, header.phase());
        assertEquals("delivery", header.module());
        assertEquals(false, header.transactional());
        assertEquals("FEAT-DLV-002 add lookup index", header.justification());
    }

    @Test
    void rejectsAMissingHeader() throws IOException {
        Path migration =
                migration(
                        "CREATE TABLE delivery.answer (id bigint PRIMARY KEY);\n"
                                + "-- cbt:module delivery\n"
                                + "-- cbt:transactional true\n"
                                + "-- cbt:justification change schema\n");

        var failure =
                assertThrows(InvalidMigrationHeaderException.class, () -> parser.parse(migration));

        assertTrue(failure.getMessage().contains(migration.toString() + ":1"));
        assertTrue(failure.getMessage().contains("missing or malformed cbt:phase"));
        assertTrue(failure.getMessage().contains("expected directives"));
    }

    @Test
    void rejectsAnUnknownPhase() throws IOException {
        Path migration =
                migration(
                        "-- cbt:phase UNKNOWN\n"
                                + "-- cbt:module delivery\n"
                                + "-- cbt:transactional true\n"
                                + "-- cbt:justification change schema\n");

        var failure =
                assertThrows(InvalidMigrationHeaderException.class, () -> parser.parse(migration));

        assertTrue(failure.getMessage().contains(migration.toString() + ":1"));
        assertTrue(failure.getMessage().contains("unknown phase 'UNKNOWN'"));
    }

    @Test
    void rejectsAMalformedDirective() throws IOException {
        Path migration =
                migration(
                        "-- cbt:phase EXPAND\n"
                                + "-- cbt:module=delivery\n"
                                + "-- cbt:transactional true\n"
                                + "-- cbt:justification change schema\n");

        var failure =
                assertThrows(InvalidMigrationHeaderException.class, () -> parser.parse(migration));

        assertTrue(failure.getMessage().contains(migration.toString() + ":2"));
        assertTrue(failure.getMessage().contains("missing or malformed cbt:module"));
    }

    @Test
    void rejectsAnUnknownModule() throws IOException {
        Path migration =
                migration(
                        "-- cbt:phase EXPAND\n"
                                + "-- cbt:module unknown\n"
                                + "-- cbt:transactional true\n"
                                + "-- cbt:justification add table\n");

        var failure =
                assertThrows(InvalidMigrationHeaderException.class, () -> parser.parse(migration));

        assertTrue(failure.getMessage().contains("unknown module 'unknown'"));
    }

    private Path migration(String content) throws IOException {
        Path migration = directory.resolve("V2__change.sql");
        Files.writeString(migration, content);
        return migration;
    }
}
