package org.meldtech.platform.platform.infra.runtime;

import java.util.Map;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoleManifest.Route;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
class RuntimeAdmissionConfiguration {
    @Bean
    static RuntimeRoutePolicies runtimeRoutePolicies(Environment environment) {
        return new RuntimeRoutePolicies(RuntimeRoleConfiguration.resolve(environment));
    }

    @Bean
    static RouteAdmission routeAdmission(RuntimeRoutePolicies policies) {
        return new RouteAdmission(policies.all());
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    WebFilter runtimeAdmissionFilter(RouteAdmission admission) {
        return (exchange, chain) ->
                Mono.defer(
                        () -> {
                            if (exchange.getRequest().getPath().value().startsWith("/health/")) {
                                return chain.filter(exchange);
                            }
                            var request = new RequestAdmission(admission);
                            return Mono.usingWhen(
                                            Mono.just(request),
                                            admitted ->
                                                    Mono.firstWithSignal(
                                                            Mono.defer(
                                                                    () -> chain.filter(exchange)),
                                                            admitted.deadline()),
                                            RequestAdmission::releaseAfterCleanup,
                                            (admitted, failure) -> admitted.releaseAfterCleanup(),
                                            RequestAdmission::releaseAfterCleanup)
                                    .contextWrite(
                                            context ->
                                                    context.put(RequestAdmission.class, request)
                                                            .put(
                                                                    TransactionCleanup.class,
                                                                    request.cleanup()))
                                    .doOnError(
                                            RuntimeUnavailableException.class,
                                            failure ->
                                                    exchange.getResponse()
                                                            .getHeaders()
                                                            .set("Retry-After", "1"));
                        });
    }

    @Bean
    static BeanPostProcessor matchedRouteAdmission(
            RuntimeRoutePolicies policies, Map<String, RuntimeLane> lanes) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof HandlerAdapter adapter
                        ? new AdmittingAdapter(adapter, policies, lanes)
                        : bean;
            }
        };
    }

    @Bean
    SmartInitializingSingleton routePolicyCoverage(
            RuntimeRoutePolicies policies, ConfigurableApplicationContext context) {
        return () -> {
            var actual = BusinessRouteInventory.inspect(context);
            var expected =
                    policies.all().stream()
                            .map(policy -> new Route(policy.method(), policy.path()))
                            .toList();
            if (actual.size() != expected.size() || !actual.containsAll(expected)) {
                throw new IllegalStateException(
                        "Every business route requires exactly one runtime policy");
            }
        };
    }

    private record AdmittingAdapter(
            HandlerAdapter delegate, RuntimeRoutePolicies policies, Map<String, RuntimeLane> lanes)
            implements HandlerAdapter, Ordered {
        @Override
        public boolean supports(Object handler) {
            return delegate.supports(handler);
        }

        @Override
        public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
            if (exchange.getRequest().getPath().value().startsWith("/health/")) {
                return delegate.handle(exchange, handler);
            }
            return Mono.deferContextual(
                    context -> {
                        RuntimeRoutePolicy policy = policies.matched(exchange);
                        RequestAdmission request = context.get(RequestAdmission.class);
                        request.select(policy);
                        RuntimeLane lane =
                                lanes.get(
                                        policy.workload() == RuntimeRoutePolicy.Workload.EXAM
                                                ? "examPathLane"
                                                : "administrativeLane");
                        Mono<HandlerResult> result =
                                Mono.defer(() -> delegate.handle(exchange, handler));
                        return lane == null ? result : lane.execute(() -> result);
                    });
        }

        @Override
        public int getOrder() {
            return delegate instanceof Ordered ordered
                    ? ordered.getOrder()
                    : Ordered.LOWEST_PRECEDENCE;
        }
    }
}
