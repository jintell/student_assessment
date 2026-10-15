package org.meldtech.platform.platform.infra.runtime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoleManifest.EntryPoint;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

/** Checks entry-point identities before construction and their contracts before lifecycle start. */
final class RuntimeManifestVerifier
        implements BeanFactoryPostProcessor, SmartInitializingSingleton, Ordered {
    private final RuntimeRole role;
    private final RuntimeRoleManifest manifest;
    private final Map<String, Candidate> candidates = new HashMap<>();
    private @Nullable ConfigurableListableBeanFactory beans;
    private @Nullable List<EntryPoint> verifiedEntries;

    RuntimeManifestVerifier(RuntimeRole role, RuntimeRoleManifest manifest) {
        this.role = role;
        this.manifest = manifest;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
        RuntimeCandidateCatalogue.validateProduction();
        beans = factory;
        for (String name : factory.getBeanDefinitionNames()) {
            Class<?> type = factory.getType(name, false);
            if (type != null && isEntryPoint(type)) {
                Candidate candidate = candidate(factory, name, type);
                requireRole(candidate);
                candidates.put(name, candidate);
            }
        }
        assertIdentities(candidates.values().stream().map(Candidate::identity).toList());
    }

    @Override
    public void afterSingletonsInstantiated() {
        verifiedEntries = null;
        ConfigurableListableBeanFactory factory = java.util.Objects.requireNonNull(beans);
        List<EntryPoint> actual = new ArrayList<>();
        for (var item : candidates.entrySet()) {
            Object bean = factory.getBean(item.getKey());
            Candidate candidate = item.getValue();
            if (!AopUtils.getTargetClass(bean).equals(candidate.type())) {
                throw RuntimeRoleManifest.invalid("runtime type differs from candidate metadata");
            }
            actual.add(describe(bean, candidate, factory));
        }
        // Also inspect singleton registrations that can bypass ordinary bean definitions.
        for (String name : factory.getSingletonNames()) {
            Object singleton = factory.getSingleton(name);
            if (singleton != null
                    && isEntryPoint(AopUtils.getTargetClass(singleton))
                    && !candidates.containsKey(name)) {
                throw RuntimeRoleManifest.invalid("untracked entry point " + name);
            }
        }
        for (RequestMappingHandlerMapping mapping :
                factory.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (var handler : mapping.getHandlerMethods().values()) {
                if (candidates.values().stream()
                        .noneMatch(candidate -> candidate.type().equals(handler.getBeanType()))) {
                    throw RuntimeRoleManifest.invalid("untracked annotated route");
                }
            }
        }
        if (actual.size() != manifest.entryPoints().size()
                || !new HashSet<>(actual).equals(new HashSet<>(manifest.entryPoints()))) {
            throw RuntimeRoleManifest.invalid(
                    "registered entry-point metadata differs from manifest");
        }
        verifiedEntries =
                actual.stream()
                        .sorted(
                                java.util.Comparator.comparing(EntryPoint::kind)
                                        .thenComparing(EntryPoint::identity)
                                        .thenComparing(entry -> String.valueOf(entry.path())))
                        .toList();
    }

    List<EntryPoint> verifiedEntries() {
        if (verifiedEntries == null) {
            throw RuntimeRoleManifest.invalid("inventory has not been verified");
        }
        return verifiedEntries;
    }

    private void assertIdentities(List<String> actual) {
        Set<String> expected =
                new TreeSet<>(manifest.entryPoints().stream().map(EntryPoint::identity).toList());
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        if (!missing.isEmpty()
                || !extra.isEmpty()
                || new HashSet<>(actual).size() != actual.size()) {
            throw RuntimeRoleManifest.invalid(
                    "entry points missing="
                            + missing
                            + ", extra="
                            + extra
                            + ", duplicate="
                            + (new HashSet<>(actual).size() != actual.size()));
        }
    }

    static boolean isEntryPoint(Class<?> type) {
        return RouterFunction.class.isAssignableFrom(type)
                || SweepDefinition.class.isAssignableFrom(type)
                || AnnotatedElementUtils.hasAnnotation(type, Controller.class)
                || isBrokerConsumer(type);
    }

    static boolean isBrokerConsumer(Class<?> type) {
        return MessageListenerContainer.class.isAssignableFrom(type)
                || AnnotatedElementUtils.hasAnnotation(type, RabbitListener.class)
                || Arrays.stream(type.getDeclaredMethods())
                        .anyMatch(
                                method ->
                                        AnnotatedElementUtils.hasAnnotation(
                                                method, RabbitListener.class));
    }

    private static Candidate candidate(
            ConfigurableListableBeanFactory factory, String name, Class<?> type) {
        var definition = factory.getBeanDefinition(name);
        String factoryName = definition.getFactoryBeanName();
        String methodName = definition.getFactoryMethodName();
        if (methodName == null) {
            return new Candidate(type, null, null);
        }
        Class<?> owner;
        if (factoryName != null) {
            owner = factory.getType(factoryName, false);
        } else if (definition instanceof AnnotatedBeanDefinition annotated
                && annotated.getFactoryMethodMetadata() != null) {
            owner =
                    ClassUtils.resolveClassName(
                            annotated.getFactoryMethodMetadata().getDeclaringClassName(),
                            factory.getBeanClassLoader());
        } else {
            String className = definition.getBeanClassName();
            owner =
                    className == null
                            ? null
                            : ClassUtils.resolveClassName(className, factory.getBeanClassLoader());
        }
        if (owner == null) {
            throw RuntimeRoleManifest.invalid("unknown factory type");
        }
        owner = ClassUtils.getUserClass(owner);
        List<Method> methods =
                Arrays.stream(owner.getDeclaredMethods())
                        .filter(method -> method.getName().equals(methodName))
                        .toList();
        if (methods.size() != 1) {
            throw RuntimeRoleManifest.invalid("ambiguous entry-point factory");
        }
        return new Candidate(type, owner, methods.getFirst());
    }

    private void requireRole(Candidate candidate) {
        Set<String> roles =
                RuntimeCandidateCatalogue.declaredRoles(
                        candidate.type(), candidate.factoryMethod());
        if (!roles.contains(role.profile())) {
            throw RuntimeRoleManifest.invalid("entry point is forbidden in active role");
        }
    }

    private static EntryPoint describe(
            Object bean, Candidate candidate, ConfigurableListableBeanFactory factory) {
        String type = candidate.type().getName();
        String factoryType =
                candidate.factoryType() == null ? null : candidate.factoryType().getName();
        String factoryMethod =
                candidate.factoryMethod() == null ? null : candidate.factoryMethod().getName();
        if (bean instanceof PolicyProtectedRoute route) {
            var descriptor = route.descriptor();
            RegisteredRouteInspector.verify(route);
            return new EntryPoint(
                    type,
                    RuntimeEntryPointDescriptors.routeKind(descriptor.pathTemplate()),
                    descriptor.routeId(),
                    descriptor.method().name(),
                    descriptor.pathTemplate(),
                    null,
                    null,
                    factoryType,
                    factoryMethod);
        }
        if (bean instanceof SweepDefinition sweep) {
            return new EntryPoint(
                    type,
                    "sweep",
                    null,
                    null,
                    null,
                    sweep.metadata().name(),
                    null,
                    factoryType,
                    factoryMethod);
        }
        if (AnnotatedElementUtils.hasAnnotation(candidate.type(), Controller.class)) {
            return RuntimeEntryPointDescriptors.controller(
                    candidate.type(), factoryType, factoryMethod, factory);
        }
        if (!(bean instanceof MessageListenerContainer)) {
            return RuntimeEntryPointDescriptors.consumer(
                    candidate.type(), factoryType, factoryMethod);
        }
        throw RuntimeRoleManifest.invalid(
                "entry point has no supported manifest descriptor: " + type);
    }

    private record Candidate(
            Class<?> type, @Nullable Class<?> factoryType, @Nullable Method factoryMethod) {
        String identity() {
            return type.getName()
                    + (factoryType == null
                            ? ""
                            : "@"
                                    + factoryType.getName()
                                    + "#"
                                    + java.util.Objects.requireNonNull(factoryMethod).getName());
        }
    }
}
