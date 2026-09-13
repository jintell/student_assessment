package org.meldtech.platform.platform.api;

import java.util.function.Function;
import org.meldtech.platform.shared.api.RequestTenantId;
import reactor.core.publisher.Mono;

/** Published entry point for the single ADR-023 exam-entry transaction. */
public interface TransactionalCollaboration {

    <T> Mono<T> inExamEntryTransaction(
            RequestTenantId tenantId, Function<TransactionalConnection, Mono<T>> work);
}
