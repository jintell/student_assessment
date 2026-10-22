package org.meldtech.platform.platform.infra.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("cbt.outbox.backlog")
record OutboxBacklogProperties(
        @DefaultValue("60s") Duration interval,
        @DefaultValue("120s") Duration maximumSampleAge,
        @DefaultValue("2s") Duration queryTimeout) {

    OutboxBacklogProperties {
        if (interval.compareTo(Duration.ofSeconds(1)) < 0
                || interval.compareTo(Duration.ofDays(1)) > 0
                || queryTimeout.compareTo(Duration.ofMillis(1)) < 0
                || queryTimeout.compareTo(Duration.ofSeconds(4)) >= 0
                || maximumSampleAge.compareTo(interval.plus(queryTimeout)) <= 0) {
            throw new IllegalArgumentException(
                    "Invalid outbox collection interval, expiry or timeout");
        }
    }
}
