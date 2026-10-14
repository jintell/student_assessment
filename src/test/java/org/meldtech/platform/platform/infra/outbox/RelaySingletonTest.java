package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.SweepLockLostException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RelaySingletonTest {

    @Test
    void contentionDoesNotInvokeWork() {
        SweepLock lock = mock(SweepLock.class);
        when(lock.tryAcquire("outbox-relay")).thenReturn(Mono.empty());
        var invoked = new AtomicBoolean();
        StepVerifier.create(
                        relay(lock)
                                .runIfLeader(
                                        "outbox-relay",
                                        () -> {
                                            invoked.set(true);
                                            return Mono.empty();
                                        }))
                .expectNext(false)
                .verifyComplete();
        assertThat(invoked).isFalse();
    }

    @Test
    void releasesAfterCommittedWork() {
        SweepLock.Handle handle = handle();
        StepVerifier.create(
                        relay(name -> Mono.just(handle)).runIfLeader("outbox-relay", Mono::empty))
                .expectNext(true)
                .verifyComplete();
        verify(handle).close();
    }

    @Test
    void releasesOnFailure() {
        SweepLock.Handle handle = handle();
        StepVerifier.create(
                        relay(name -> Mono.just(handle))
                                .runIfLeader(
                                        "outbox-relay",
                                        () -> Mono.error(new IllegalStateException("tick failed"))))
                .expectErrorMessage("tick failed")
                .verify();
        verify(handle).close();
    }

    @Test
    void releasesOnCancellation() {
        SweepLock.Handle handle = handle();
        StepVerifier.create(
                        relay(name -> Mono.just(handle)).runIfLeader("outbox-relay", Mono::never))
                .thenCancel()
                .verify();
        verify(handle).close();
    }

    @Test
    void lockLossCancelsUnfinishedWork() {
        SweepLock.Handle handle = handle();
        when(handle.assertHeld())
                .thenReturn(Mono.empty())
                .thenReturn(Mono.error(new SweepLockLostException()));
        var cancelled = new AtomicBoolean();
        StepVerifier.withVirtualTime(
                        () ->
                                relay(name -> Mono.just(handle))
                                        .runIfLeader(
                                                "outbox-relay",
                                                () ->
                                                        Mono.<Void>never()
                                                                .doOnCancel(
                                                                        () -> cancelled.set(true))))
                .thenAwait(Duration.ofMillis(100))
                .expectError(SweepLockLostException.class)
                .verify();
        assertThat(cancelled).isTrue();
        verify(handle).close();
    }

    private static SweepLock.Handle handle() {
        SweepLock.Handle handle = mock(SweepLock.Handle.class);
        when(handle.assertHeld()).thenReturn(Mono.empty());
        when(handle.close()).thenReturn(Mono.empty());
        return handle;
    }

    private static RelaySingleton relay(SweepLock lock) {
        return new RelaySingleton(lock, mock(OutboxTelemetry.class), Clock.systemUTC());
    }
}
