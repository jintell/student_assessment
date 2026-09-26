package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

public final class AssessmentId extends AbstractUuidIdentifier {

    private AssessmentId(UUID value) {
        super(value);
    }

    public static AssessmentId parse(String value) {
        return new AssessmentId(parseCanonical(value, "AssessmentId"));
    }

    public static AssessmentId newId(IdGenerator generator) {
        return new AssessmentId(generateVersionSeven(generator, "AssessmentId"));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof AssessmentId other && valueEquals(other);
    }
}
