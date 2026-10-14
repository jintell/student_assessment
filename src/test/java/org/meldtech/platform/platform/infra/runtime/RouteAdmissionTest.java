package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoutePolicy.Workload;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RouteAdmissionTest {
    @Test
    void routeClassAndSharedGeneralLimitsDoNotBorrowExamCapacity() {
        var first = policy("first", Workload.ADMINISTRATIVE);
        var second = policy("second", Workload.ADMINISTRATIVE);
        var report = policy("report", Workload.REPORT);
        var exam = policy("exam", Workload.EXAM);
        var gate = new RouteAdmission(List.of(first, second, report, exam));
        var leases = new ArrayList<RouteAdmission.Lease>();
        try {
            for (int i = 0; i < 8; i++) {
                leases.add(gate.acquire(first));
            }
            assertThatThrownBy(() -> gate.acquire(first))
                    .isInstanceOf(RuntimeUnavailableException.class);
            for (int i = 0; i < 4; i++) {
                leases.add(gate.acquire(second));
            }
            assertThatThrownBy(() -> gate.acquire(report))
                    .isInstanceOf(RuntimeUnavailableException.class);
            leases.add(gate.acquire(exam));
            leases.getFirst().close();
            leases.getFirst().close();
            leases.add(gate.acquire(report));
            assertThatThrownBy(() -> gate.acquire(report))
                    .isInstanceOf(RuntimeUnavailableException.class);
        } finally {
            leases.forEach(RouteAdmission.Lease::close);
        }
        try (var firstReport = gate.acquire(report);
                var secondReport = gate.acquire(report)) {
            assertThat(firstReport).isNotSameAs(secondReport);
            assertThatThrownBy(() -> gate.acquire(report))
                    .isInstanceOf(RuntimeUnavailableException.class);
        }
    }

    @Test
    void responseStreamingHoldsPermitsAndCancellationReleasesThem() {
        var policies = new RuntimeRoutePolicies(RuntimeRole.API);
        var gate = new RouteAdmission(policies.all());
        var filter = new RuntimeAdmissionConfiguration().runtimeAdmissionFilter(gate);
        var postProcessor = RuntimeAdmissionConfiguration.matchedRouteAdmission(policies, Map.of());
        var adapter =
                (HandlerAdapter)
                        java.util.Objects.requireNonNull(
                                postProcessor.postProcessAfterInitialization(
                                        new EmptyAdapter(), "handler"));
        var subscriptions = new ArrayList<Disposable>();
        try {
            for (int i = 0; i < 8; i++) {
                subscriptions.add(
                        filter.filter(
                                        exchange(),
                                        incoming ->
                                                adapter.handle(incoming, this).then(Mono.never()))
                                .subscribe());
            }
            var rejected = exchange();
            StepVerifier.create(
                            filter.filter(
                                    rejected, incoming -> adapter.handle(incoming, this).then()))
                    .expectError(RuntimeUnavailableException.class)
                    .verify();
            assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
            subscriptions.removeFirst().dispose();
            StepVerifier.create(
                            filter.filter(
                                    exchange(), incoming -> adapter.handle(incoming, this).then()))
                    .verifyComplete();
        } finally {
            subscriptions.forEach(Disposable::dispose);
        }
        StepVerifier.create(
                        filter.filter(
                                exchange(), incoming -> adapter.handle(incoming, this).then()))
                .verifyComplete();
    }

    @Test
    void deadlineCancelsWorkButWaitsForTransactionCleanup() {
        var policy = policy("deadline", Workload.EXAM);
        var gate = new RouteAdmission(List.of(policy));
        var filter = new RuntimeAdmissionConfiguration().runtimeAdmissionFilter(gate);
        var cleanup = new java.util.concurrent.atomic.AtomicReference<TransactionCleanup>();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        StepVerifier.withVirtualTime(
                        () ->
                                filter.filter(
                                        exchange(),
                                        ignored ->
                                                Mono.deferContextual(
                                                        context -> {
                                                            RequestAdmission request =
                                                                    context.get(
                                                                            RequestAdmission.class);
                                                            request.select(policy);
                                                            TransactionCleanup active =
                                                                    context.get(
                                                                            TransactionCleanup
                                                                                    .class);
                                                            active.acquired();
                                                            cleanup.set(active);
                                                            return Mono.<Void>never()
                                                                    .doOnCancel(
                                                                            () ->
                                                                                    cancelled.set(
                                                                                            true));
                                                        })))
                .thenAwait(java.time.Duration.ofSeconds(2))
                .then(() -> assertThat(cancelled).isTrue())
                .expectNoEvent(java.time.Duration.ofMillis(500))
                .then(() -> java.util.Objects.requireNonNull(cleanup.get()).released())
                .expectError(RuntimeUnavailableException.class)
                .verify();
        var leases = new ArrayList<RouteAdmission.Lease>();
        try {
            for (int i = 0; i < 32; i++) {
                leases.add(gate.acquire(policy));
            }
        } finally {
            leases.forEach(RouteAdmission.Lease::close);
        }
    }

    @Test
    void closingBeforeSelectionCannotLeakAPermit() {
        var policy = policy("closed", Workload.REPORT);
        var gate = new RouteAdmission(List.of(policy));
        var request = new RequestAdmission(gate);
        request.close();
        assertThatThrownBy(() -> request.select(policy))
                .isInstanceOf(RuntimeUnavailableException.class);
        try (var lease = gate.acquire(policy)) {
            assertThat(lease).isNotNull();
        }
    }

    private static RuntimeRoutePolicy policy(String id, Workload workload) {
        return new RuntimeRoutePolicy(id, "GET", "/" + id, workload);
    }

    private static MockServerWebExchange exchange() {
        String path = "/api/v1/platform/conformance-reference";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        exchange.getAttributes()
                .put(
                        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        PathPatternParser.defaultInstance.parse(path));
        return exchange;
    }

    private static final class EmptyAdapter implements HandlerAdapter {
        @Override
        public boolean supports(Object handler) {
            return true;
        }

        @Override
        public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
            return Mono.empty();
        }
    }
}
