package org.meldtech.platform.shared.kernel.outbox;

import java.util.Objects;
import java.util.regex.Pattern;

public record AggregateReference(String aggregateType, String aggregateId) {

    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9.]{0,127}");

    public AggregateReference {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (!TYPE.matcher(aggregateType).matches()) {
            throw new IllegalArgumentException("aggregateType must be a stable type name");
        }
        if (aggregateId.isBlank()
                || !aggregateId.equals(aggregateId.trim())
                || aggregateId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "aggregateId must be non-blank typed identifier text");
        }
    }
}
