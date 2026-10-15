package org.meldtech.platform.platform.infra.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.meldtech.platform.conformance.fixtures.runtime.domain.ScopedDomain;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepBatchResult;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepStopSignal;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

class RuntimeRoleConformanceTests {
    private static final ArchRule RULE =
            classes()
                    .should(
                            new ArchCondition<>(
                                    "declare exactly one direct closed role at each runtime registration") {
                                @Override
                                public void check(JavaClass item, ConditionEvents events) {
                                    try {
                                        RuntimeCandidateCatalogue.validate(List.of(item.reflect()));
                                    } catch (IllegalStateException failure) {
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        item,
                                                        "RUNTIME_ROLE_DECLARATION: "
                                                                + item.getName()
                                                                + ": "
                                                                + failure.getMessage()));
                                    }
                                }
                            });

    @Test
    void stageFourChecksAllProductionRolesIncludingInactiveProfiles() {
        RULE.check(new ClassFileImporter().importPath(Path.of("build/classes/java/main")));
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void rejectsMissingAmbiguousInvalidAndWrongCategoryDeclarations(Class<?> fixture) {
        assertThatThrownBy(() -> RULE.check(new ClassFileImporter().importClasses(fixture)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RUNTIME_ROLE_DECLARATION");
    }

    @Test
    void acceptsDirectRoutesWorkersSharedSupportAndUniqueFactories() {
        RULE.check(
                new ClassFileImporter()
                        .importClasses(
                                ApiEndpoint.class,
                                PinEndpoint.class,
                                WorkerConsumer.class,
                                WorkerSweep.class,
                                SharedSupport.class,
                                ValidFactory.class));
    }

    static Stream<Class<?>> invalidDeclarations() {
        return Stream.of(
                MissingEndpoint.class,
                BareProfile.class,
                EmptyRoles.class,
                DuplicateRoles.class,
                InvalidRole.class,
                MetaOnly.class,
                DirectAndMeta.class,
                WorkerRoute.class,
                ApiConsumer.class,
                UndeclaredFactory.class,
                DoublyDeclaredFactory.class,
                InheritedConfigurationRole.class,
                ScopedDomain.class,
                OrdinaryPinRoute.class,
                ApiSweep.class,
                MissingSweep.class,
                UnregisteredPinRoute.class,
                DoublyDeclaredEndpointFactory.class);
    }

    @RestController
    static class MissingEndpoint {}

    @RestController
    @Profile("api")
    static class BareProfile {}

    @RestController
    @RoleScoped({})
    static class EmptyRoles {}

    @RestController
    @RoleScoped({"api", "api"})
    static class DuplicateRoles {}

    @RestController
    @RoleScoped("api | worker")
    static class InvalidRole {}

    @RestController
    @ApiRole
    static class MetaOnly {}

    @RestController
    @ApiRole
    @RoleScoped("api")
    static class DirectAndMeta {}

    @RestController
    @RoleScoped("worker")
    static class WorkerRoute {}

    @RestController
    @RoleScoped("api")
    static class ApiEndpoint {}

    @RestController
    @RoleScoped("pindist")
    static class PinEndpoint {
        @GetMapping("/api/v1/exam-sessions/{id}/pin-distribution")
        public String read() {
            return "fixture";
        }
    }

    @RestController
    @RoleScoped("pindist")
    static class OrdinaryPinRoute {
        @GetMapping("/ordinary")
        public String read() {
            return "fixture";
        }
    }

    @Component
    @RoleScoped({"api", "worker", "pindist"})
    static class SharedSupport {}

    @Component
    @RoleScoped("pindist")
    static class UnregisteredPinRoute implements PolicyProtectedRoute {
        @Override
        public RouteDescriptor descriptor() {
            throw new UnsupportedOperationException("static fixture");
        }

        @Override
        public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
            return Mono.empty();
        }
    }

    abstract static class FixtureSweep implements SweepDefinition {
        @Override
        public SweepMetadata metadata() {
            throw new UnsupportedOperationException("static fixture");
        }

        @Override
        public Mono<SweepBatchResult> runBatch(SweepStopSignal stop) {
            return Mono.empty();
        }
    }

    @Component
    @RoleScoped("worker")
    static class WorkerSweep extends FixtureSweep {}

    @Component
    @RoleScoped("api")
    static class ApiSweep extends FixtureSweep {}

    @Component
    static class MissingSweep extends FixtureSweep {}

    @Retention(RetentionPolicy.RUNTIME)
    @RoleScoped("api")
    @interface ApiRole {}

    @Component
    @RoleScoped("api")
    static class ApiConsumer {
        @RabbitListener(queues = "fixture")
        public void accept(String value) {}
    }

    @Component
    @RoleScoped("worker")
    static class WorkerConsumer {
        @RabbitListener(queues = "fixture")
        public void accept(String value) {}
    }

    static class UnscopedConsumer {
        @RabbitListener(queues = "fixture")
        public void accept(String value) {}
    }

    @Configuration(proxyBeanMethods = false)
    static class ValidFactory {
        @Bean
        @RoleScoped("worker")
        UnscopedConsumer consumer() {
            return new UnscopedConsumer();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UndeclaredFactory {
        @Bean
        UnscopedConsumer consumer() {
            return new UnscopedConsumer();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DoublyDeclaredFactory {
        @Bean
        @RoleScoped("worker")
        WorkerConsumer consumer() {
            return new WorkerConsumer();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DoublyDeclaredEndpointFactory {
        @Bean
        @RoleScoped("api")
        ApiEndpoint endpoint() {
            return new ApiEndpoint();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @RoleScoped("worker")
    static class InheritedConfigurationRole {
        @Bean
        UnscopedConsumer consumer() {
            return new UnscopedConsumer();
        }
    }
}
