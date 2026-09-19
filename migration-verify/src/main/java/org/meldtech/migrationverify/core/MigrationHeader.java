package org.meldtech.migrationverify.core;

import java.nio.file.Path;

public record MigrationHeader(
        Path source,
        MigrationPhase phase,
        String module,
        boolean transactional,
        String justification) {}
