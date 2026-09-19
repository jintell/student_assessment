package org.meldtech.platform.platform.deployment;

import java.time.Duration;
import org.meldtech.platform.platform.api.DeployFreezeRefusalRecorder;
import org.meldtech.platform.platform.api.SessionWindowQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DeployFreezeConfiguration {

    @Bean
    DeployFreezePrecondition deployFreezePrecondition(
            SessionWindowQuery sessionWindowQuery,
            @Value("${cbt.deployment.session-window-timeout:5s}") Duration queryTimeout,
            ObjectProvider<DeployFreezeRefusalRecorder> refusalRecorder) {
        return new DeployFreezePrecondition(
                sessionWindowQuery,
                queryTimeout,
                refusalRecorder.getIfAvailable(() -> ignored -> {}));
    }
}
