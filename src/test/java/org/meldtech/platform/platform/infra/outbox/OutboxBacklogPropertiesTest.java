package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxBacklogPropertiesTest {
    @Test
    void rejectsUnboundedTimeoutAndExpiryThatCannotCoverOneCollection() {
        for (Duration timeout :
                new Duration[] {Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(4)}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new OutboxBacklogProperties(
                                            Duration.ofMinutes(1), Duration.ofMinutes(2), timeout));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new OutboxBacklogProperties(
                                        Duration.ofMinutes(1),
                                        Duration.ofSeconds(62),
                                        Duration.ofSeconds(2)));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new OutboxBacklogProperties(
                                        Duration.ofMillis(200),
                                        Duration.ofMinutes(2),
                                        Duration.ofSeconds(2)));
    }
}
