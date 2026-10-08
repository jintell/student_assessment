package org.meldtech.platform.shared.kernel.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RetentionHorizon(Optional<Instant> retainedUntil) {

    public RetentionHorizon {
        Objects.requireNonNull(retainedUntil, "retainedUntil");
    }

    public static RetentionHorizon until(Instant retainedUntil) {
        return new RetentionHorizon(Optional.of(retainedUntil));
    }

    public static RetentionHorizon indefinite() {
        return new RetentionHorizon(Optional.empty());
    }

    public boolean isIndefinite() {
        return retainedUntil.isEmpty();
    }
}
