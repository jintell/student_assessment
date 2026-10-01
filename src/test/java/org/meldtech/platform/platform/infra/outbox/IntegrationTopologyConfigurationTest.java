package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class IntegrationTopologyConfigurationTest {

    @Test
    void workerProfileDeclaresTheDurableQuorumTopology() {
        try (var context = contextWithProfile("worker")) {
            Declarables topology = context.getBean(Declarables.class);

            assertThat(topology.getDeclarablesByType(TopicExchange.class))
                    .extracting(TopicExchange::getName)
                    .containsExactlyInAnyOrder("integration", "integration.dlx");
            assertThat(topology.getDeclarablesByType(Queue.class))
                    .hasSize(IntegrationTopologyConfiguration.CONTEXTS.size() + 1)
                    .allSatisfy(
                            queue ->
                                    assertThat(queue.getArguments())
                                            .containsEntry("x-queue-type", "quorum"));
            assertThat(topology.getDeclarablesByType(Queue.class))
                    .filteredOn(queue -> !queue.getName().equals("integration.dlq"))
                    .allSatisfy(
                            queue ->
                                    assertThat(queue.getArguments())
                                            .containsEntry(
                                                    "x-dead-letter-exchange",
                                                    "integration.dlx"));
            assertThat(topology.getDeclarablesByType(Binding.class))
                    .hasSize(IntegrationTopologyConfiguration.CONTEXTS.size() + 1);
        }
    }

    @Test
    void apiProfileDoesNotDeclareBrokerTopology() {
        try (var context = contextWithProfile("api")) {
            assertThat(context.getBeansOfType(Declarables.class)).isEmpty();
        }
    }

    private static AnnotationConfigApplicationContext contextWithProfile(String profile) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.register(IntegrationTopologyConfiguration.class);
        context.refresh();
        return context;
    }
}
