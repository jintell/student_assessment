package org.meldtech.platform.platform.infra.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.platform.shared.api.IsolationOperation;
import org.meldtech.platform.shared.api.IsolationScenarioProvider;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.RouteDescriptor;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

class TenantIsolationMatrixGeneratorTest {

    private final TenantIsolationMatrixGenerator generator = new TenantIsolationMatrixGenerator();

    @Test
    void emitsThreeAssertionsForEveryTenantRoute(@TempDir Path directory) throws IOException {
        PolicyProtectedRoute route = route("delivery.submitAnswer");
        IsolationScenarioProvider provider = provider("delivery.submitAnswer");

        TenantIsolationMatrixGenerator.IsolationMatrix matrix =
                generator.generate(List.of(route), List.of(provider));
        Path output = directory.resolve("isolation-matrix.json");
        generator.write(matrix, output);

        assertThat(matrix.assertions())
                .extracting(TenantIsolationMatrixGenerator.AssertionRow::operation)
                .containsExactly(
                        IsolationOperation.READ,
                        IsolationOperation.WRITE,
                        IsolationOperation.ENUMERATE);
        assertThat(output).content().contains("delivery.submitAnswer").contains("404");
    }

    @Test
    void rejectsAnEndpointAbsentFromScenarioCoverage() {
        assertThatThrownBy(() -> generator.generate(List.of(route("delivery.route")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing=[delivery.route]");
    }

    private static IsolationScenarioProvider provider(String routeId) {
        return new IsolationScenarioProvider() {
            @Override
            public String routeId() {
                return routeId;
            }

            @Override
            public java.util.Set<IsolationOperation> operations() {
                return EnumSet.allOf(IsolationOperation.class);
            }
        };
    }

    private static PolicyProtectedRoute route(String routeId) {
        return new PolicyProtectedRoute() {
            @Override
            public RouteDescriptor descriptor() {
                return RouteDescriptor.tenant(routeId, HttpMethod.POST, "/answers", "delivery");
            }

            @Override
            public Mono<HandlerFunction<ServerResponse>> route(ServerRequest request) {
                return Mono.empty();
            }

            @Override
            public void accept(RouterFunctions.Visitor visitor) {}
        };
    }
}
