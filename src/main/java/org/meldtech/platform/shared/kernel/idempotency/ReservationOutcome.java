package org.meldtech.platform.shared.kernel.idempotency;

import java.util.Objects;

public sealed interface ReservationOutcome
        permits ReservationOutcome.Reserved,
                ReservationOutcome.Replay,
                ReservationOutcome.Unavailable {

    record Reserved(ReservationToken token) implements ReservationOutcome {
        public Reserved {
            Objects.requireNonNull(token, "token");
        }
    }

    record Replay(StoredResponse response) implements ReservationOutcome {
        public Replay {
            Objects.requireNonNull(response, "response");
        }
    }

    enum Unavailable implements ReservationOutcome {
        INSTANCE
    }
}
