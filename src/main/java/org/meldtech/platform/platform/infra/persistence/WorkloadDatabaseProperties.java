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
