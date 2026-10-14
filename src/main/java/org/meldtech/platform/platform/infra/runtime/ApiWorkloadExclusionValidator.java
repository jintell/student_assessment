package org.meldtech.platform.platform.infra.runtime;

import org.meldtech.platform.platform.api.RoleScoped;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/** Rejects background execution entry points before an API context constructs them. */
@Component
@RoleScoped("api")
final class ApiWorkloadExclusionValidator implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
        for (String name : factory.getBeanDefinitionNames()) {
            Class<?> type = factory.getType(name, false);
            if (type != null && RuntimeIntervalSource.class.isAssignableFrom(type)) {
                throw invalid("interval source", type);
            }
            if (type != null && RuntimeManifestVerifier.isBrokerConsumer(type)) {
                throw invalid("broker consumer", type);
            }
        }
    }

    private static IllegalStateException invalid(String kind, Class<?> type) {
        return RuntimeRoleManifest.invalid("api role contains " + kind + " " + type.getName());
    }
}
