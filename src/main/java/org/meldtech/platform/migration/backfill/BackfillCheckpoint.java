package org.meldtech.platform.migration.backfill;

import java.util.Optional;

public record BackfillCheckpoint<K>(
        String definitionChecksum, Optional<K> exclusiveCursor, K upperBound, long processedRows) {}
