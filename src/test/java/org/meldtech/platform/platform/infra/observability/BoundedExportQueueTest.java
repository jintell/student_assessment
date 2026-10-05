package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.common.CompletableResultCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

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
