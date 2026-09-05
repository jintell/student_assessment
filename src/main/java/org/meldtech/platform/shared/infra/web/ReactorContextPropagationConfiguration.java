package org.meldtech.platform.shared.infra.web;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Configuration(proxyBeanMethods = false)
class ReactorContextPropagationConfiguration {

    @PostConstruct
    void enableAutomaticPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcCorrelationIdAccessor());
        Hooks.enableAutomaticContextPropagation();
    }

    @PreDestroy
    void disableAutomaticPropagation() {
        Hooks.disableAutomaticContextPropagation();
        ContextRegistry.getInstance().removeThreadLocalAccessor(MdcCorrelationIdAccessor.KEY);
    }
}
