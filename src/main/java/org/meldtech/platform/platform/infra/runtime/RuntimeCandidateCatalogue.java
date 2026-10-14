package org.meldtech.platform.platform.infra.runtime;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/** Inspects the production artifact without applying Spring's profile conditions. */
final class RuntimeCandidateCatalogue {
    private RuntimeCandidateCatalogue() {}

    static void validateProduction() {
        String anchor =
                ClassUtils.convertClassNameToResourcePath(RuntimeCandidateCatalogue.class.getName())
                        + ".class";
        var resolver = new PathMatchingResourcePatternResolver();
        var metadata = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> types = new ArrayList<>();
        try {
            String catalogueUrl =
                    new ClassPathResource(anchor, RuntimeCandidateCatalogue.class.getClassLoader())
                            .getURL()
                            .toExternalForm();
            String artifactRoot =
                    catalogueUrl.substring(0, catalogueUrl.length() - anchor.length());
            for (var resource :
                    resolver.getResources("classpath*:org/meldtech/platform/**/*.class")) {
                // Test fixtures and other artifacts must not become production candidates.
                if (resource.getURL().toExternalForm().startsWith(artifactRoot)) {
                    String name =
                            metadata.getMetadataReader(resource).getClassMetadata().getClassName();
                    types.add(ClassUtils.resolveClassName(name, resolver.getClassLoader()));
                }
            }
        } catch (IOException exception) {
            throw RuntimeRoleManifest.invalid("candidate catalogue unavailable");
        }
        if (types.isEmpty()) {
            throw RuntimeRoleManifest.invalid("candidate catalogue is empty");
        }
        validate(types);
    }

    static void validate(List<Class<?>> types) {
        for (Class<?> type : types) {
            boolean scoped = AnnotatedElementUtils.hasAnnotation(type, RoleScoped.class);
            if (type.getPackageName().contains(".domain") && scoped) {
                throw RuntimeRoleManifest.invalid("role declaration in domain: " + type.getName());
            }
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            if ((AnnotatedElementUtils.hasAnnotation(type, Component.class)
                            && RuntimeManifestVerifier.isEntryPoint(type))
                    || (type.getSimpleName().equals("Endpoint")
                            && type.getPackageName().contains(".slice."))) {
                validateCategory(type, declaredRoles(type, null));
            } else if (scoped) {
                declaredRoles(type, null);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Bean.class)
                        && RuntimeManifestVerifier.isEntryPoint(method.getReturnType())) {
                    validateCategory(
                            method.getReturnType(), declaredRoles(method.getReturnType(), method));
                }
            }
        }
    }

    static Set<String> declaredRoles(Class<?> type, @Nullable Method factoryMethod) {
        long declarations =
                MergedAnnotations.from(type, MergedAnnotations.SearchStrategy.DIRECT).stream(
                                RoleScoped.class)
                        .count();
        if (factoryMethod != null) {
            declarations += MergedAnnotations.from(factoryMethod).stream(RoleScoped.class).count();
        }
        RoleScoped onType = type.getDeclaredAnnotation(RoleScoped.class);
        RoleScoped onMethod =
                factoryMethod == null
                        ? null
                        : factoryMethod.getDeclaredAnnotation(RoleScoped.class);
        if (declarations != 1 || (onType == null) == (onMethod == null)) {
            throw RuntimeRoleManifest.invalid(
                    "entry point requires exactly one direct role declaration: "
                            + type.getName()
                            + (factoryMethod == null ? "" : "@" + factoryMethod.getName()));
        }
        RoleScoped declaration =
                onType == null ? java.util.Objects.requireNonNull(onMethod) : onType;
        Set<String> roles = new HashSet<>();
        for (String value : declaration.value()) {
            if (java.util.Arrays.stream(RuntimeRole.values())
                            .noneMatch(role -> role.profile().equals(value))
                    || !roles.add(value)) {
                throw RuntimeRoleManifest.invalid("invalid or duplicate role declaration");
            }
        }
        if (roles.isEmpty()) {
            throw RuntimeRoleManifest.invalid("empty role declaration");
        }
        return Set.copyOf(roles);
    }

    private static void validateCategory(Class<?> type, Set<String> roles) {
        if (org.meldtech.platform.platform.api.SweepDefinition.class.isAssignableFrom(type)
                || RuntimeManifestVerifier.isBrokerConsumer(type)) {
            if (!roles.equals(Set.of("worker"))) {
                throw RuntimeRoleManifest.invalid(
                        "background entry point must declare worker only: " + type.getName());
            }
        } else if (roles.contains("worker")) {
            throw RuntimeRoleManifest.invalid(
                    "business route cannot declare worker: " + type.getName());
        } else if (roles.contains("pindist")) {
            validatePinRoute(type, roles);
        }
    }

    private static void validatePinRoute(Class<?> type, Set<String> roles) {
        if (!roles.equals(Set.of("pindist"))) {
            throw RuntimeRoleManifest.invalid(
                    "PIN distribution cannot share a business role: " + type.getName());
        }
        // Functional paths are opaque to reflection, so require the committed PIN inventory.
        // The descriptor and actual router are compared again at boot.
        if (PolicyProtectedRoute.class.isAssignableFrom(type)) {
            boolean registered =
                    RuntimeRoleManifest.load(RuntimeRole.PINDIST).entryPoints().stream()
                            .anyMatch(
                                    entry ->
                                            entry.type().equals(type.getName())
                                                    && "GET".equals(entry.method())
                                                    && PindistRouteValidator.PATH.equals(
                                                            entry.path()));
            if (!registered) {
                throw RuntimeRoleManifest.invalid(
                        "unregistered PIN distribution route: " + type.getName());
            }
            return;
        }
        var mappings =
                java.util.Arrays.stream(type.getDeclaredMethods())
                        .map(
                                method ->
                                        AnnotatedElementUtils.findMergedAnnotation(
                                                method, RequestMapping.class))
                        .filter(java.util.Objects::nonNull)
                        .toList();
        if (mappings.size() != 1
                || mappings.getFirst().path().length != 1
                || !mappings.getFirst().path()[0].equals(PindistRouteValidator.PATH)
                || !java.util.Arrays.equals(
                        mappings.getFirst().method(), new RequestMethod[] {RequestMethod.GET})
                || AnnotatedElementUtils.hasAnnotation(type, RequestMapping.class)) {
            throw RuntimeRoleManifest.invalid(
                    "unclassified PIN distribution route: " + type.getName());
        }
    }
}
