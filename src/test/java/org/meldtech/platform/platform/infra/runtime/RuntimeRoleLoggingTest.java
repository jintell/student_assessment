package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meldtech.platform.platform.api.RuntimeRole;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class RuntimeRoleLoggingTest {
    @ParameterizedTest
    @EnumSource(RuntimeRole.class)
    void loggingUsesResolvedRoleWithoutThreadLocalState(RuntimeRole role) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(role.profile());
        environment.setProperty("logging.structured.json.add.role", "WRONG");
        new RuntimeRoleEnvironmentPostProcessor()
                .postProcessEnvironment(
                        environment, new SpringApplication(RuntimeRoleConfiguration.class));
        assertThat(environment.getProperty("logging.structured.json.add.role"))
                .isEqualTo(role.name());
        assertThat(environment.getProperty("logging.pattern.level"))
                .contains("[role:" + role.name() + "]");
        var context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        try {
            context.putObject(Environment.class.getName(), environment);
            var encoder = new StructuredLogEncoder();
            encoder.setContext(context);
            encoder.setFormat("logstash");
            encoder.start();
            var event =
                    new LoggingEvent(
                            getClass().getName(),
                            context.getLogger("framework"),
                            Level.INFO,
                            "background message",
                            null,
                            null);
            String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
            assertThat(new ObjectMapper().readTree(line).path("role").stringValue())
                    .isEqualTo(role.name());
            encoder.stop();
        } finally {
            context.stop();
        }
    }
}
