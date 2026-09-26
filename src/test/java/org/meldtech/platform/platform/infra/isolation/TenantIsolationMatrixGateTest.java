package org.meldtech.platform.platform.infra.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.PostgreSqlTestContainer;
import org.meldtech.platform.RedisTestContainer;
import org.meldtech.platform.shared.api.IsolationOperation;
import org.meldtech.platform.shared.api.IsolationScenarioProvider;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import(TenantIsolationMatrixGateTest.IsolationGateConfiguration.class)
@SpringBootTest
class TenantIsolationMatrixGateTest {

    private static final Path MATRIX_OUTPUT =
            Path.of("build/generated/isolation/tenant-isolation-matrix.json");

    private final TenantIsolationMatrixGenerator generator = new TenantIsolationMatrixGenerator();

    @Autowired private List<PolicyProtectedRoute> routes;
    @Autowired private List<IsolationScenarioProvider> providers;

    @Test
    void everyTenantRouteHasCompleteIsolationCoverage() throws IOException {
        TenantIsolationMatrixGenerator.IsolationMatrix matrix =
                generator.generate(routes, providers);

        generator.write(matrix, MATRIX_OUTPUT);

        assertThat(matrix.routeIds()).isNotEmpty();
        assertThat(matrix.assertions()).hasSize(matrix.routeIds().size() * 3);
        assertThat(MATRIX_OUTPUT).exists();
    }

    @Test
    void removingEndpointCoverageFailsTheGate() {
        assertThat(providers).isNotEmpty();
        IsolationScenarioProvider removed = providers.getFirst();
        List<IsolationScenarioProvider> incompleteProviders =
                providers.stream().filter(provider -> provider != removed).toList();

        assertThatThrownBy(() -> generator.generate(routes, incompleteProviders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing=[" + removed.routeId() + "]");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolationGateConfiguration {

        @Bean(destroyMethod = "")
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return PostgreSqlTestContainer.instance();
        }

        @Bean(destroyMethod = "")
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return RedisTestContainer.instance();
        }

        @Bean
        IsolationScenarioProvider conformanceReferenceIsolationScenarios() {
            return new IsolationScenarioProvider() {
                @Override
                public String routeId() {
                    return "platform.getConformanceReference";
                }

                @Override
                public java.util.Set<IsolationOperation> operations() {
                    return EnumSet.allOf(IsolationOperation.class);
                }
            };
        }
    }
}
