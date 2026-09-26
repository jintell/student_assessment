package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

public final class CorrectionRequestId extends AbstractUuidIdentifier {

    private CorrectionRequestId(UUID value) {
        super(value);
    }

    public static CorrectionRequestId parse(String value) {
        return new CorrectionRequestId(parseCanonical(value, "CorrectionRequestId"));
    }

    public static CorrectionRequestId newId(IdGenerator generator) {
        return new CorrectionRequestId(generateVersionSeven(generator, "CorrectionRequestId"));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof CorrectionRequestId other && valueEquals(other);
    }
}
