package org.meldtech.platform.platform.infra.runtime;

import io.r2dbc.spi.ConnectionFactory;
import org.meldtech.platform.platform.api.RoleScoped;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
@RoleScoped("api")
class RuntimeBulkheadConfiguration {
    @Bean(destroyMethod = "dispose")
    Scheduler examPathScheduler() {
        return Schedulers.newBoundedElastic(8, 32, "exam-path", 60);
    }

    @Bean(destroyMethod = "dispose")
    Scheduler administrativeScheduler() {
        return Schedulers.newBoundedElastic(2, 16, "admin-report", 60);
    }

    @Bean
    RuntimeLane examPathLane(
            @Qualifier("examPathScheduler") Scheduler scheduler,
            @Qualifier("examPathConnectionFactory") ConnectionFactory connections) {
        return new RuntimeLane(scheduler, connections);
    }

    @Bean
    RuntimeLane administrativeLane(
            @Qualifier("administrativeScheduler") Scheduler scheduler,
            @Qualifier("apiConnectionFactory") ConnectionFactory connections) {
        return new RuntimeLane(scheduler, connections);
    }
}
