package org.meldtech.platform.audit.application;

import java.util.Objects;

public record ActiveLegalHold(String holdReference, String legalBasisReference) {

    public ActiveLegalHold {
        holdReference = requireText(holdReference, "holdReference");
        legalBasisReference = requireText(legalBasisReference, "legalBasisReference");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
