package org.meldtech.platform.platform.infra.outbox;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
public class IntegrationTopologyConfiguration {

    static final String INTEGRATION_EXCHANGE = "integration";
    static final String DEAD_LETTER_EXCHANGE = "integration.dlx";
    static final String DEAD_LETTER_QUEUE = "integration.dlq";
    static final List<String> CONTEXTS =
            List.of(
                    "tenancy",
                    "iam",
                    "academic",
                    "people",
                    "questionbank",
                    "authoring",
                    "examaccess",
                    "delivery",
                    "grading",
                    "result",
                    "correction",
                    "notification");

    @Bean
    Declarables integrationTopology() {
        TopicExchange integration = new TopicExchange(INTEGRATION_EXCHANGE, true, false);
        TopicExchange deadLetters = new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();

        List<Declarable> topology = new ArrayList<>();
        topology.add(integration);
        topology.add(deadLetters);
        topology.add(deadLetterQueue);
        topology.add(BindingBuilder.bind(deadLetterQueue).to(deadLetters).with("#"));

        for (String context : CONTEXTS) {
            Queue queue =
                    QueueBuilder.durable("integration." + context)
                            .quorum()
                            .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                            .build();
            topology.add(queue);
            topology.add(BindingBuilder.bind(queue).to(integration).with(context + ".#"));
        }

        return new Declarables(topology);
    }
}
