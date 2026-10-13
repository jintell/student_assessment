package org.meldtech.platform.platform.infra.runtime;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.infra.persistence.DiscardableConnection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class TransactionSweepLockTest {

    private final ConnectionFactory connections = mock(ConnectionFactory.class);
    private final DiscardableConnection connection = mock(DiscardableConnection.class);
    private final SessionSweepLock.Commands commands = mock(SessionSweepLock.Commands.class);
    private final TransactionSweepLock lock = new TransactionSweepLock(connections, commands);

    @BeforeEach
    void configureConnection() {
        doReturn(Mono.just(connection)).when(connections).create();
        when(connection.beginTransaction()).thenReturn(Mono.empty());
        when(connection.rollbackTransaction()).thenReturn(Mono.empty());
        when(connection.close()).thenReturn(Mono.empty());
        when(connection.discard()).thenReturn(Mono.empty());
    }

    @Test
    void loserRollsBackAndReturnsLeaseWithoutWaitingOrCommitting() {
        when(commands.tryAcquire(connection, "retention"))
                .thenReturn(Mono.just(new SessionSweepLock.Acquisition(41L, false)));
        StepVerifier.create(lock.tryAcquire("retention")).verifyComplete();
        verify(commands).tryAcquire(connection, "retention");
        verify(connection).rollbackTransaction();
        verify(connection).close();
        verify(connection, never()).commitTransaction();
        verify(connection, never()).discard();
    }

    @Test
    void winnerRetainsTransactionUntilIdempotentRollback() {
        SweepLock.Handle handle = acquire();
        verify(connection, never()).rollbackTransaction();
        verify(connection, never()).close();
        when(commands.isHeld(connection, 41L)).thenReturn(Mono.just(true));
        StepVerifier.create(handle.assertHeld().then(handle.close())).verifyComplete();
        StepVerifier.create(handle.close()).verifyComplete();
        verify(connection, times(1)).rollbackTransaction();
        verify(connection, times(1)).close();
        verify(connection, never()).commitTransaction();
        verify(commands, never()).release(connection, 41L);
    }

    @Test
    void acquisitionCancellationDiscardsTheUncertainTransaction() {
        when(commands.tryAcquire(connection, "retention")).thenReturn(Mono.never());
        StepVerifier.create(lock.tryAcquire("retention"))
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();
        verify(connection).discard();
        verify(connection, never()).close();
    }

    @Test
    void rollbackFailureDiscardsInsteadOfReturningTheLease() {
        SweepLock.Handle handle = acquire();
        when(connection.rollbackTransaction())
                .thenReturn(Mono.error(new IllegalStateException("rollback failed")));
        StepVerifier.create(handle.close()).expectErrorMessage("rollback failed").verify();
        verify(connection).discard();
        verify(connection, never()).close();
    }

    @Test
    void rollbackWaitsForTheOwnershipCheck() {
        SweepLock.Handle handle = acquire();
        Sinks.One<Boolean> held = Sinks.one();
        when(commands.isHeld(connection, 41L)).thenReturn(held.asMono());
        StepVerifier.create(Flux.merge(handle.assertHeld(), handle.close()))
                .then(
                        () -> {
                            verify(connection, never()).rollbackTransaction();
                            held.tryEmitValue(true).orThrow();
                        })
                .verifyComplete();
        verify(connection).rollbackTransaction();
        StepVerifier.create(handle.assertHeld())
                .expectErrorMessage("Sweep advisory lock handle is closing or closed")
                .verify();
    }

    @Test
    void lostLockDiscardsThePinnedBackend() {
        SweepLock.Handle handle = acquire();
        when(commands.isHeld(connection, 41L)).thenReturn(Mono.just(false));
        StepVerifier.create(handle.assertHeld())
                .expectErrorMessage("Sweep advisory lock is no longer held")
                .verify();
        verify(connection).discard();
    }

    private SweepLock.Handle acquire() {
        when(commands.tryAcquire(connection, "retention"))
                .thenReturn(Mono.just(new SessionSweepLock.Acquisition(41L, true)));
        return Objects.requireNonNull(lock.tryAcquire("retention").block(Duration.ofSeconds(5)));
    }
}
