package org.meldtech.platform.platform.infra.runtime;

import java.util.Arrays;
import java.util.EnumSet;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class RuntimeRoleConfiguration {

    @Bean
    static BeanFactoryPostProcessor runtimeRoleAssertion(Environment environment) {
        return beanFactory -> beanFactory.registerSingleton("runtimeRole", resolve(environment));
    }

    static RuntimeRole resolve(Environment environment) {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        var roles = EnumSet.noneOf(RuntimeRole.class);
        for (RuntimeRole role : RuntimeRole.values()) {
            if (profiles.contains(role.profile())) {
                roles.add(role);
            }
        }
        if (roles.size() != 1) {
            throw new IllegalStateException(
                    "RUNTIME_ROLE_INVALID: exactly one active role is required: api, worker, pindist");
        }
        return roles.iterator().next();
    }
}
