package org.meldtech.platform.shared.infra.web;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Configuration(proxyBeanMethods = false)
class ReactorContextPropagationConfiguration {

    private static final java.util.List<String> LOG_FIELDS =
            java.util.List.of(
                    RequestContextPropagation.CORRELATION_ID_KEY,
                    RequestContextPropagation.ACTOR_TYPE_KEY,
                    RequestContextPropagation.ACTOR_ID_KEY,
                    RequestContextPropagation.TENANT_ID_KEY);

    @PostConstruct
    void enableAutomaticPropagation() {
        LOG_FIELDS.forEach(
                field ->
                        ContextRegistry.getInstance()
                                .registerThreadLocalAccessor(new MdcFieldAccessor(field)));
        Hooks.enableAutomaticContextPropagation();
    }

    @PreDestroy
    void disableAutomaticPropagation() {
        Hooks.disableAutomaticContextPropagation();
        LOG_FIELDS.forEach(ContextRegistry.getInstance()::removeThreadLocalAccessor);
    }
}
