package org.meldtech.platform.platform.api;

import java.util.function.Function;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

/** Published entry point for the single ADR-023 exam-entry transaction. */
public interface TransactionalCollaboration {

    <T> Mono<T> inExamEntryTransaction(
            TenantId tenantId, Function<TransactionalConnection, Mono<T>> work);
}
