package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoleManifest.EntryPoint;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.PolicyResolver;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class RuntimeManifestVerifierTest {
    @Test
    void committedManifestsMatchAllThreeIsolatedContexts() throws ClassNotFoundException {
        Class<?> endpoint =
                Class.forName(
                        "org.meldtech.platform.platform.slice.getConformanceReference.Endpoint");
        Class<?> handler =
                Class.forName(
                        "org.meldtech.platform.platform.slice.getConformanceReference.Handler");
        for (RuntimeRole role : RuntimeRole.values()) {
            withMock(runner(role), handler)
                    .withUserConfiguration(endpoint, WebConfiguration.class)
                    .withBean(PolicyResolver.class, () -> mock(PolicyResolver.class))
                    .withBean(RequestTelemetry.class, () -> mock(RequestTelemetry.class))
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context.getBeansOfType(PolicyProtectedRoute.class))
                                        .hasSize(role == RuntimeRole.API ? 1 : 0);
                                writeCandidate(
                                        role,
                                        context.getBean(RuntimeManifestVerifier.class)
                                                .verifiedEntries());
                            });
        }
    }

    @Test
    void excludedProductionCandidatesStillRequireDeclarations() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                RuntimeCandidateCatalogue.validate(
                                        List.of(ExcludedUndeclaredEndpoint.class)))
                .withMessageContaining("exactly one direct role declaration");
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> RuntimeCandidateCatalogue.validate(List.of(ExcludedFactory.class)))
                .withMessageContaining("exactly one direct role declaration");
        RuntimeCandidateCatalogue.validate(List.of(StaticFactory.class));
    }

    @Test
    void staticFactoryIdentityIncludesItsDeclaringTypeAndMethod() {
        var entry =
                new EntryPoint(
                        FactoryRoute.class.getName(),
                        "slice-endpoint",
                        "platform.factory",
                        "GET",
                        "/factory",
                        null,
                        null,
                        StaticFactory.class.getName(),
                        "route");
        fixtureRunner(RuntimeRole.API, entry)
                .withUserConfiguration(StaticFactory.class, WebConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void annotatedRoutesAndConsumerQueuesAreComparedExactly() {
        var endpoint =
                new EntryPoint(
                        AnnotatedEndpoint.class.getName(),
                        "slice-endpoint",
                        "platform.annotated",
                        "GET",
                        "/annotated",
                        null,
                        null,
                        null,
                        null);
        fixtureRunner(RuntimeRole.API, endpoint)
                .withUserConfiguration(AnnotatedEndpoint.class, WebConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
        var consumer =
                new EntryPoint(
                        Consumer.class.getName(),
                        "broker-consumer",
                        null,
                        null,
                        null,
                        null,
                        "grading.reference",
                        null,
                        null);
        fixtureRunner(RuntimeRole.WORKER, consumer)
                .withUserConfiguration(Consumer.class)
                .run(context -> assertThat(context).hasNotFailed());
        var wrongQueue =
                new EntryPoint(
                        Consumer.class.getName(),
                        "broker-consumer",
                        null,
                        null,
                        null,
                        null,
                        "grading.wrong",
                        null,
                        null);
        fixtureRunner(RuntimeRole.WORKER, wrongQueue)
                .withUserConfiguration(Consumer.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("metadata differs");
                        });
    }

    private static ApplicationContextRunner fixtureRunner(RuntimeRole role, EntryPoint entry) {
        RuntimeRoleManifest baseline = RuntimeRoleManifest.load(role);
        var manifest =
                new RuntimeRoleManifest(
                        baseline.schemaVersion(),
                        baseline.status(),
                        baseline.role(),
                        baseline.workload(),
                        baseline.enabledCategories(),
                        baseline.forbiddenCategories(),
                        baseline.businessRoutes(),
                        List.of(entry),
                        baseline.management(),
                        baseline.scalingSignals(),
                        baseline.intendedCapabilities(),
                        baseline.prewarming(),
                        baseline.serviceAccount(),
                        baseline.ownsDatabaseSchema(),
                        baseline.replicas(),
                        baseline.autoscaling());
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + role.profile())
                .withBean(
                        RuntimeManifestVerifier.class,
                        () -> new RuntimeManifestVerifier(role, manifest));
    }

    private static void writeCandidate(RuntimeRole role, List<EntryPoint> entries) {
        try {
            Path directory = Path.of("build/reports/runtime-roles");
            Files.createDirectories(directory);
            JsonMapper.builder()
                    .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .build()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            directory.resolve(role.profile() + ".json").toFile(),
                            Map.of("role", role.profile(), "entryPoints", entries));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebConfiguration {}

    @RestController
    @RoleScoped("api")
    static class AnnotatedEndpoint {
        @GetMapping(path = "/annotated", name = "platform.annotated")
        Mono<String> get() {
            return Mono.just("reference");
        }
    }

    @Component
    @RoleScoped("worker")
    static class Consumer {
        @RabbitListener(queues = "grading.reference", autoStartup = "false")
        void consume(String ignored) {}
    }

    @RestController
    @Profile("pindist")
    static class ExcludedUndeclaredEndpoint {}

    @Configuration(proxyBeanMethods = false)
    @Profile("pindist")
    static class ExcludedFactory {
        @Bean
        static FactoryRoute route() {
            return new FactoryRoute();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StaticFactory {
        @Bean
        @RoleScoped("api")
        static FactoryRoute route() {
            return new FactoryRoute();
        }
    }

    static class FactoryRoute implements PolicyProtectedRoute {
        private final RouterFunction<ServerResponse> router =
                RouterFunctions.route(
                        RequestPredicates.GET("/factory"), request -> ServerResponse.ok().build());

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant(
                    "platform.factory", HttpMethod.GET, "/factory", "platform");
        }

        @Override
        public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return router.route(request);
        }

        @Override
        public void accept(RouterFunctions.Visitor visitor) {
            router.accept(visitor);
        }
    }

    @Test
    void missingEntryFailsBeforeLifecycleStart() {
        AtomicBoolean started = new AtomicBoolean();
        runner(RuntimeRole.API)
                .withBean(SmartLifecycle.class, () -> new Listener(started))
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("missing=")
                                    .hasMessageContaining("getConformanceReference.Endpoint");
                            assertThat(started).isFalse();
                        });
    }

    @Test
    void extraEntryFailsBeforeItsConstructorRuns() {
        AtomicBoolean constructed = new AtomicBoolean();
        runner(RuntimeRole.WORKER)
                .withBean(
                        ExtraRoute.class,
                        () -> {
                            constructed.set(true);
                            return new ExtraRoute();
                        })
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("extra=")
                                    .hasMessageContaining("ExtraRoute");
                            assertThat(constructed).isFalse();
                        });
    }

    @Test
    void listenerContainerWithoutRoleFailsBeforeConstruction() {
        AtomicBoolean constructed = new AtomicBoolean();
        runner(RuntimeRole.WORKER)
                .withBean(
                        MessageListenerContainer.class,
                        () -> {
                            constructed.set(true);
                            return mock(MessageListenerContainer.class);
                        })
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining("exactly one direct role declaration");
                            assertThat(constructed).isFalse();
                        });
    }

    // Exercise rejection of the legacy predicate while Spring supports it.
    @Test
    @SuppressWarnings("removal")
    void pathExtensionPredicatesRemainUnsupported() {
        var route = new ExtraRoute();
        route.router =
                RouterFunctions.route(
                        RequestPredicates.GET("/extra")
                                .and(RequestPredicates.pathExtension("json")),
                        request -> ServerResponse.ok().build());

        assertThatIllegalStateException()
                .isThrownBy(() -> RegisteredRouteInspector.verify(route))
                .withMessageContaining("route predicate requires explicit manifest support");
    }

    @Test
    void registeredPredicateMustMatchDescriptorAndContainOnlyOneRoute() {
        var route = new ExtraRoute();
        RegisteredRouteInspector.verify(route);
        route.router =
                RouterFunctions.route(
                        RequestPredicates.POST("/extra"), request -> ServerResponse.ok().build());
        assertThatIllegalStateException().isThrownBy(() -> RegisteredRouteInspector.verify(route));
        route.router =
                RouterFunctions.route(
                                RequestPredicates.GET("/extra"),
                                request -> ServerResponse.ok().build())
                        .andRoute(
                                RequestPredicates.GET("/hidden"),
                                request -> ServerResponse.ok().build());
        assertThatIllegalStateException().isThrownBy(() -> RegisteredRouteInspector.verify(route));
    }

    @Test
    void malformedManifestsFailClosed() {
        for (String content :
                List.of(
                        "{}",
                        "{\"schemaVersion\":1,\"schemaVersion\":1}",
                        "{\"unexpected\":true}")) {
            assertThatIllegalStateException()
                    .isThrownBy(
                            () ->
                                    RuntimeRoleManifest.read(
                                            new ByteArrayInputStream(
                                                    content.getBytes(StandardCharsets.UTF_8)),
                                            RuntimeRole.API));
        }
    }

    private static ApplicationContextRunner runner(RuntimeRole role) {
        return new ApplicationContextRunner()
                .withUserConfiguration(RuntimeManifestConfiguration.class)
                .withPropertyValues("spring.profiles.active=" + role.profile());
    }

    private static <T> ApplicationContextRunner withMock(
            ApplicationContextRunner runner, Class<T> type) {
        return runner.withBean(type, () -> mock(type));
    }

    private record Listener(AtomicBoolean started) implements SmartLifecycle {
        @Override
        public void start() {
            started.set(true);
        }

        @Override
        public void stop() {
            started.set(false);
        }

        @Override
        public boolean isRunning() {
            return started.get();
        }
    }

    @RoleScoped("worker")
    private static final class ExtraRoute implements PolicyProtectedRoute {
        private RouterFunction<ServerResponse> router =
                RouterFunctions.route(
                        RequestPredicates.GET("/extra"), request -> ServerResponse.ok().build());

        @Override
        public RouteDescriptor descriptor() {
            return RouteDescriptor.tenant("platform.extra", HttpMethod.GET, "/extra", "platform");
        }

        @Override
        public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return router.route(request);
        }

        @Override
        public void accept(RouterFunctions.Visitor visitor) {
            router.accept(visitor);
        }
    }
}
