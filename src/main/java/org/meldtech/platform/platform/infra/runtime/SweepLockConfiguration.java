package org.meldtech.platform.platform.infra.runtime;

import org.meldtech.platform.platform.api.RoleScoped;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@RoleScoped("worker")
class SweepLockConfiguration {

    @Bean
    static BeanFactoryPostProcessor sweepLockModeValidator(Environment environment) {
        return beanFactory -> {
            String mode = environment.getProperty("cbt.runtime.sweep-lock.mode", "session");
            String pooling =
                    environment.getProperty("cbt.runtime.sweep-lock.pooling-mode", "direct");
            if (!"session".equals(mode) && !"transaction".equals(mode)) {
                throw new IllegalStateException("Sweep lock mode must be session or transaction");
            }
            if (!"direct".equals(pooling) && !"transaction".equals(pooling)) {
                throw new IllegalStateException("Sweep pooling mode must be direct or transaction");
            }
            if ("transaction".equals(pooling) && !"transaction".equals(mode)) {
                throw new IllegalStateException(
                        "Transaction pooling requires transaction sweep locks");
            }
        };
    }
}
