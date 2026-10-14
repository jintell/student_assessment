package org.meldtech.platform.platform.infra.runtime;

import java.time.Duration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorResourceFactory;

@Configuration(proxyBeanMethods = false)
class GracefulShutdownConfiguration {
    @Bean
    static BeanPostProcessor boundedReactorShutdown() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof ReactorResourceFactory resources) {
                    resources.setShutdownQuietPeriod(Duration.ZERO);
                    resources.setShutdownTimeout(Duration.ofSeconds(1));
                }
                return bean;
            }
        };
    }
}
