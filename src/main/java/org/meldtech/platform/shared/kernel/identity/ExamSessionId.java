package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

public final class ExamSessionId extends AbstractUuidIdentifier {

    private ExamSessionId(UUID value) {
        super(value);
    }

    public static ExamSessionId parse(String value) {
        return new ExamSessionId(parseCanonical(value, "ExamSessionId"));
    }

    public static ExamSessionId newId(IdGenerator generator) {
        return new ExamSessionId(generateVersionSeven(generator, "ExamSessionId"));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof ExamSessionId other && valueEquals(other);
    }
}
