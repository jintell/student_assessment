package org.meldtech.migrationverify.policy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.meldtech.migrationverify.core.MigrationHeader;
import org.meldtech.migrationverify.core.MigrationPhase;

public final class MigrationHeaderParser {

    private static final List<String> DIRECTIVES =
            List.of("phase", "module", "transactional", "justification");
    private static final String EXPECTED =
            "expected directives, in order: cbt:phase, cbt:module, "
                    + "cbt:transactional, cbt:justification";
    private static final Set<String> MODULES =
            Set.of(
                    "academic",
                    "audit",
                    "authoring",
                    "correction",
                    "delivery",
                    "examaccess",
                    "grading",
                    "iam",
                    "notification",
                    "outbox",
                    "people",
                    "platform",
                    "questionbank",
                    "result",
                    "tenancy");

    public MigrationHeader parse(Path source) {
        List<String> lines = readHeader(source);
        String phaseValue = directiveValue(source, lines, 0, DIRECTIVES.get(0));
        String module = directiveValue(source, lines, 1, DIRECTIVES.get(1));
        String transactionalValue = directiveValue(source, lines, 2, DIRECTIVES.get(2));
        String justification = directiveValue(source, lines, 3, DIRECTIVES.get(3));

        MigrationPhase phase;
        try {
            phase = MigrationPhase.valueOf(phaseValue);
        } catch (IllegalArgumentException exception) {
            throw invalid(source, 1, "unknown phase '" + phaseValue + "'");
        }
        if (!MODULES.contains(module)) {
            throw invalid(source, 2, "unknown module '" + module + "'");
        }
        if (!transactionalValue.equals("true") && !transactionalValue.equals("false")) {
            throw invalid(
                    source,
                    3,
                    "transactional value must be exactly 'true' or 'false', found '"
                            + transactionalValue
                            + "'");
        }
        if (justification.isBlank()) {
            throw invalid(source, 4, "justification must not be blank");
        }

        return new MigrationHeader(
                source, phase, module, Boolean.parseBoolean(transactionalValue), justification);
    }

    private static List<String> readHeader(Path source) {
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            var lines = new java.util.ArrayList<String>(4);
            for (int index = 0; index < 4; index++) {
                String line = reader.readLine();
                if (line == null) {
                    throw invalid(source, index + 1, "header is incomplete");
                }
                if (index == 0 && line.startsWith("\uFEFF")) {
                    throw invalid(source, 1, "UTF-8 byte-order mark is not permitted");
                }
                lines.add(line);
            }
            return List.copyOf(lines);
        } catch (IOException exception) {
            throw new InvalidMigrationHeaderException(
                    source + ": cannot read migration header: " + exception.getMessage());
        }
    }

    private static String directiveValue(
            Path source, List<String> lines, int index, String directive) {
        String prefix = "-- cbt:" + directive + " ";
        String line = lines.get(index);
        if (!line.startsWith(prefix)) {
            throw invalid(source, index + 1, "missing or malformed cbt:" + directive);
        }
        String value = line.substring(prefix.length()).trim();
        if (value.isEmpty() || !line.equals(prefix + value)) {
            throw invalid(source, index + 1, "invalid value for cbt:" + directive);
        }
        return value;
    }

    private static InvalidMigrationHeaderException invalid(Path source, int line, String reason) {
        return new InvalidMigrationHeaderException(
                source + ":" + line + ": " + reason + "; " + EXPECTED);
    }
}
