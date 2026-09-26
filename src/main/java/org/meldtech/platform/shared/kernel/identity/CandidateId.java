package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

public final class CandidateId extends AbstractUuidIdentifier {

    private CandidateId(UUID value) {
        super(value);
    }

    public static CandidateId parse(String value) {
        return new CandidateId(parseCanonical(value, "CandidateId"));
    }

    public static CandidateId newId(IdGenerator generator) {
        return new CandidateId(generateVersionSeven(generator, "CandidateId"));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof CandidateId other && valueEquals(other);
    }
}
