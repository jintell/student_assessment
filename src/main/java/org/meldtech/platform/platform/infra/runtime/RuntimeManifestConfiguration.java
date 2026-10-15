package org.meldtech.platform.platform.infra.runtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class RuntimeManifestConfiguration {
    @Bean
    static RuntimeManifestVerifier runtimeManifestVerifier(Environment environment) {
        var role = RuntimeRoleConfiguration.resolve(environment);
        return new RuntimeManifestVerifier(role, RuntimeRoleManifest.load(role));
    }
}
