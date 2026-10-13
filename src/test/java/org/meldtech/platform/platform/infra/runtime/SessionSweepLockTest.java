package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class SessionSweepLockTest {

    private final ConnectionFactory connections = mock(ConnectionFactory.class);
    private final DiscardableConnection connection = mock(DiscardableConnection.class);
    private final SessionSweepLock.Commands commands = mock(SessionSweepLock.Commands.class);
    private final SessionSweepLock lock = new SessionSweepLock(connections, commands);

    @BeforeEach
    void configureConnection() {
        doReturn(Mono.just(connection)).when(connections).create();
        when(connection.beginTransaction()).thenReturn(Mono.empty());
        when(connection.commitTransaction()).thenReturn(Mono.empty());
        when(connection.rollbackTransaction()).thenReturn(Mono.empty());
        when(connection.close()).thenReturn(Mono.empty());
        when(connection.discard()).thenReturn(Mono.empty());
    }

    @Test
    void contentionReturnsEmptyAfterOneNonBlockingAttempt() {
        when(commands.tryAcquire(connection, "retention"))
                .thenReturn(Mono.just(new SessionSweepLock.Acquisition(41L, false)));

        StepVerifier.create(lock.tryAcquire("retention")).verifyComplete();

        verify(commands, times(1)).tryAcquire(connection, "retention");
        verify(connection).close();
        verify(connection, never()).discard();
    }

    @Test
    void cancellationDuringAcquisitionDiscardsTheConnection() {
        when(commands.tryAcquire(connection, "retention")).thenReturn(Mono.never());

        StepVerifier.create(lock.tryAcquire("retention"))
                .expectSubscription()
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();

        verify(commands, times(1)).tryAcquire(connection, "retention");
        verify(connection, times(1)).rollbackTransaction();
        verify(connection, times(1)).discard();
        verify(connection, never()).close();
    }

    @Test
    void successfulReleaseReturnsTheConnectionAndIsIdempotent() {
        when(commands.tryAcquire(connection, "retention"))
                .thenReturn(Mono.just(new SessionSweepLock.Acquisition(41L, true)));
        when(commands.release(connection, 41L)).thenReturn(Mono.just(true));
        SweepLock.Handle handle = Objects.requireNonNull(lock.tryAcquire("retention").block());

        StepVerifier.create(handle.close()).verifyComplete();
        StepVerifier.create(handle.close()).verifyComplete();

        verify(commands, times(1)).release(connection, 41L);
        verify(connection, times(1)).close();
        verify(connection, never()).discard();
    }

    @Test
    void releaseWaitsForAnInFlightOwnershipCheck() {
        when(commands.tryAcquire(connection, "retention"))
                .thenReturn(Mono.just(new SessionSweepLock.Acquisition(41L, true)));
        Sinks.One<Boolean> held = Sinks.one();
        when(commands.isHeld(connection, 41L)).thenReturn(held.asMono());
        when(commands.release(connection, 41L)).thenReturn(Mono.just(true));
        SweepLock.Handle handle = Objects.requireNonNull(lock.tryAcquire("retention").block());

        StepVerifier.create(Flux.merge(handle.assertHeld(), handle.close()))
                .then(
                        () -> {
                            verify(commands, never()).release(connection, 41L);
                            held.tryEmitValue(true).orThrow();
                        })
                .verifyComplete();

        verify(commands, times(1)).isHeld(connection, 41L);
        verify(commands, times(1)).release(connection, 41L);
        StepVerifier.create(handle.assertHeld())
                .expectErrorMessage("Sweep advisory lock handle is closing or closed")
                .verify();
    }

    @Test
    void failedReleaseIsLoggedAndTheConnectionIsDiscarded() {
        when(commands.tryAcquire(connection, "retention"))
                .thenReturn(Mono.just(new SessionSweepLock.Acquisition(41L, true)));
        when(commands.release(connection, 41L)).thenReturn(Mono.just(false));
        SweepLock.Handle handle = Objects.requireNonNull(lock.tryAcquire("retention").block());

        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(SessionSweepLock.class)) {
            StepVerifier.create(handle.close())
                    .expectErrorMessage("PostgreSQL reported that the sweep lock was not held")
                    .verify();
            StepVerifier.create(handle.close())
                    .expectErrorMessage("PostgreSQL reported that the sweep lock was not held")
                    .verify();

            assertThat(telemetry.logEvents())
                    .singleElement()
                    .satisfies(
                            event ->
                                    assertThat(event.getFormattedMessage())
                                            .contains("sweep_lock_release_failed")
                                            .contains("sweep_name=retention")
                                            .contains("action=discard_connection"));
        }

        verify(commands, times(1)).release(connection, 41L);
        verify(connection, times(1)).discard();
        verify(connection, never()).close();
    }
}
