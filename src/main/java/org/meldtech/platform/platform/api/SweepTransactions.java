package org.meldtech.platform.platform.api;

import java.util.function.Function;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;

/** Bounded transaction boundary for a runner-attributed sweep, not a general role-switching API. */
public interface SweepTransactions {

    <T> Mono<T> inPlatformTransaction(
            SweepDataModule module, Function<SweepConnection, Mono<T>> work);

    <T> Mono<T> inTenantTransaction(
            SweepDataModule module, TenantId tenantId, Function<SweepConnection, Mono<T>> work);
}
