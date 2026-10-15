package org.meldtech.platform.platform.infra.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Component("runtimeDrain")
@Order(Ordered.HIGHEST_PRECEDENCE)
class RuntimeDrainState
        implements ReactiveHealthIndicator,
                ApplicationListener<ContextClosedEvent>,
                DisposableBean {

    private final ApplicationContext context;
    private final Path marker;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Scheduler fileChecks = Schedulers.newBoundedElastic(1, 8, "drain-state");

    RuntimeDrainState(ApplicationContext context, Environment environment) {
        this.context = context;
        marker = Path.of(environment.getProperty("cbt.runtime.drain-marker", "/run/cbt/draining"));
        if (!marker.isAbsolute()) {
            throw new IllegalArgumentException("Drain marker must be an absolute path");
        }
    }

    boolean isDraining() {
        return draining.get();
    }

    void requestDrain() {
        if (draining.compareAndSet(false, true)) {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(
                        () -> {
                            if (!Files.notExists(marker)) {
                                requestDrain();
                            }
                            return isDraining() ? Health.down().build() : Health.up().build();
                        })
                .subscribeOn(fileChecks)
                .onErrorResume(
                        failure -> {
                            requestDrain();
                            return Mono.just(Health.down().build());
                        });
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() == context) {
            requestDrain();
        }
    }

    @Override
    public void destroy() {
        fileChecks.dispose();
    }
}
