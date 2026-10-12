package org.meldtech.platform.platform.infra.persistence;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cbt.database")
record WorkloadDatabaseProperties(Map<String, PoolProperties> pools) {

    WorkloadDatabaseProperties {
        pools = pools == null ? Map.of() : Map.copyOf(pools);
    }

    PoolProperties requiredPool(String name) {
        PoolProperties pool = pools.get(name);
        if (pool == null) {
            throw new IllegalStateException("Required database pool is missing: " + name);
        }
        return pool;
    }

    PoolProperties requiredWorkerPool(String name) {
        Map<String, Integer> allocation = Map.of("worker", 4, "grading", 4, "sweep-lock", 2);
        for (Map.Entry<String, Integer> entry : allocation.entrySet()) {
            PoolProperties pool = requiredPool(entry.getKey());
            if (pool.maxSize() != entry.getValue() || !pool.username().equals("app_worker")) {
                throw new IllegalStateException(
                        "Worker pool "
                                + entry.getKey()
                                + " must use app_worker and max-size "
                                + entry.getValue()
                                + " within the ten-connection worker budget");
            }
            if (pool.url().startsWith("r2dbc:pool:")) {
                throw new IllegalStateException("Worker pool URLs must not create a nested pool");
            }
        }
        if (!allocation.containsKey(name)) {
            throw new IllegalArgumentException("Unknown worker pool: " + name);
        }
        return requiredPool(name);
    }

    record PoolProperties(
            String url,
            String username,
            String password,
            int initialSize,
            int maxSize,
            Duration maxIdleTime) {

        PoolProperties {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Database pool URL must be configured");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("Database pool username must be configured");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("Database pool password must be configured");
            }
            if (initialSize < 0 || maxSize < 1 || initialSize > maxSize) {
                throw new IllegalArgumentException("Database pool size is invalid");
            }
            if (maxIdleTime == null || maxIdleTime.isNegative() || maxIdleTime.isZero()) {
                throw new IllegalArgumentException("Database pool max idle time must be positive");
            }
        }
    }
}
