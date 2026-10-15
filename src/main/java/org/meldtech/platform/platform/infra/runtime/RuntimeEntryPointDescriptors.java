package org.meldtech.platform.platform.infra.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.platform.infra.runtime.RuntimeRoleManifest.EntryPoint;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

final class RuntimeEntryPointDescriptors {
    private RuntimeEntryPointDescriptors() {}

    static EntryPoint controller(
            Class<?> type,
            @Nullable String factoryType,
            @Nullable String factoryMethod,
            ConfigurableListableBeanFactory factory) {
        List<EntryPoint> entries = new ArrayList<>();
        for (var mapping : factory.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            mapping.getHandlerMethods()
                    .forEach(
                            (info, handler) -> {
                                if (type.equals(handler.getBeanType())) {
                                    var methods = info.getMethodsCondition().getMethods();
                                    var patterns = info.getPatternsCondition().getPatterns();
                                    if (info.getName() == null
                                            || info.getName().isBlank()
                                            || methods.size() != 1
                                            || patterns.size() != 1) {
                                        throw RuntimeRoleManifest.invalid(
                                                "annotated route requires a name, method and path");
                                    }
                                    String path = patterns.iterator().next().getPatternString();
                                    entries.add(
                                            new EntryPoint(
                                                    type.getName(),
                                                    routeKind(path),
                                                    info.getName(),
                                                    methods.iterator().next().name(),
                                                    path,
                                                    null,
                                                    null,
                                                    factoryType,
                                                    factoryMethod));
                                }
                            });
        }
        if (entries.size() != 1) {
            throw RuntimeRoleManifest.invalid("each endpoint must register exactly one route");
        }
        return entries.getFirst();
    }

    static EntryPoint consumer(
            Class<?> type, @Nullable String factoryType, @Nullable String factoryMethod) {
        List<RabbitListener> listeners =
                new ArrayList<>(Arrays.asList(type.getAnnotationsByType(RabbitListener.class)));
        for (var method : type.getDeclaredMethods()) {
            listeners.addAll(Arrays.asList(method.getAnnotationsByType(RabbitListener.class)));
        }
        if (listeners.size() != 1
                || listeners.getFirst().queues().length != 1
                || !listeners.getFirst().queues()[0].matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}")) {
            throw RuntimeRoleManifest.invalid("consumer requires one literal queue declaration");
        }
        return new EntryPoint(
                type.getName(),
                "broker-consumer",
                null,
                null,
                null,
                null,
                listeners.getFirst().queues()[0],
                factoryType,
                factoryMethod);
    }

    static String routeKind(String path) {
        return "/api/v1/exam-sessions/{id}/pin-distribution".equals(path)
                ? "pin-distribution-endpoint"
                : "slice-endpoint";
    }
}
