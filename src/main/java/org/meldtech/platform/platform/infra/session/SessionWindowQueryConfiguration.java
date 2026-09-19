package org.meldtech.platform.platform.infra.session;

import org.meldtech.platform.platform.api.SessionWindowQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SessionWindowQueryConfiguration {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SessionWindowQueryConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SessionWindowQuery.class)
    SessionWindowQuery unknownSessionWindowQuery() {
        LOGGER.warn(
                "No authoritative SessionWindowQuery adapter is configured; deployment decisions "
                        + "will fail closed as UNKNOWN");
        return new UnknownSessionWindowQuery();
    }
}
