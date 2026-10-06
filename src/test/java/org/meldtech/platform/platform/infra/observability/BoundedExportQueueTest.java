package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.opentelemetry.sdk.common.CompletableResultCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class BoundedExportQueueTest {

    @Test
    void dropsTheOldestItemAndWaitsForSlowExportsOnlyOnItsWorker() throws Exception {
        List<String> exported = Collections.synchronizedList(new ArrayList<>());
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch laterExports = new CountDownLatch(2);
        CompletableResultCode blocked = new CompletableResultCode();
        BoundedExportQueue<String> queue =
                new BoundedExportQueue<>(
                        "test",
                        new ObservabilityProperties.Queue(2, 1, Duration.ofMinutes(1)),
                        Duration.ofMillis(500),
                        () -> Instant.parse("2026-10-05T12:00:00Z"),
                        batch ->
                                export(
                                        batch,
                                        exported,
                                        threads,
                                        firstStarted,
                                        laterExports,
                                        blocked),
                        () -> {});

        queue.offer("first");
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        queue.offer("second");
        queue.offer("third");
        queue.offer("fourth");

        assertThat(laterExports.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(exported).containsExactly("first", "third", "fourth");
        assertThat(queue.droppedCount()).isEqualTo(1L);
        assertThat(threads).allMatch(name -> name.equals("telemetry-test-exporter"));
        queue.shutdown();
    }

    @Test
    void slowSinkBeyondTheHardTimeoutNeverBlocksAReactorCaller() throws Exception {
        CompletableResultCode delayed = new CompletableResultCode();
        CountDownLatch exportStarted = new CountDownLatch(1);
        AtomicReference<String> exportThread = new AtomicReference<>();
        AtomicReference<String> callerThread = new AtomicReference<>();
        TelemetryHealth health = mock(TelemetryHealth.class);
        BoundedExportQueue<String> queue =
                new BoundedExportQueue<>(
                        "slow-sink",
                        ObservabilityHealthMetrics.Signal.TRACE,
                        new ObservabilityProperties.Queue(2, 1, Duration.ofMinutes(1)),
                        Duration.ofMillis(100),
                        Instant::now,
                        batch -> {
                            exportThread.set(Thread.currentThread().getName());
                            exportStarted.countDown();
                            return delayed;
                        },
                        () -> {},
                        health);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                Thread.sleep(Duration.ofSeconds(1));
                                delayed.succeed();
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                        });

        Instant startedAt = Instant.now();
        String response =
                Mono.fromSupplier(
                                () -> {
                                    callerThread.set(Thread.currentThread().getName());
                                    queue.offer("request-telemetry");
                                    return "accepted";
                                })
                        .subscribeOn(Schedulers.parallel())
                        .block(Duration.ofMillis(500));

        assertThat(response).isEqualTo("accepted");
        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofMillis(500));
        assertThat(exportStarted.await(500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(callerThread.get()).startsWith("parallel-");
        assertThat(exportThread.get()).isEqualTo("telemetry-slow-sink-exporter");
        verify(health, timeout(1_000)).exportTimeout(ObservabilityHealthMetrics.Signal.TRACE);
        queue.shutdown();
    }

    private static CompletableResultCode export(
            Collection<String> batch,
            List<String> exported,
            List<String> threads,
            CountDownLatch firstStarted,
            CountDownLatch laterExports,
            CompletableResultCode blocked) {
        String item = batch.iterator().next();
        exported.add(item);
        threads.add(Thread.currentThread().getName());
        if (item.equals("first")) {
            firstStarted.countDown();
            return blocked;
        }
        laterExports.countDown();
        return CompletableResultCode.ofSuccess();
    }
}
