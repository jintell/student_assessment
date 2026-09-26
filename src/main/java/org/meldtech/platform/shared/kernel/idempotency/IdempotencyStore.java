package org.meldtech.platform.shared.kernel.idempotency;

import org.reactivestreams.Publisher;

public interface IdempotencyStore {

    Publisher<ReservationOutcome> reserveOrReplay(
            IdempotencyScope scope, IdempotencyKey key, RequestFingerprint request);

    Publisher<Void> complete(ReservationToken reservation, StoredResponse response);
}
