package com.iasbank.adapters.messaging.rabbitmq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable topic exchange "iasbank.transfers" with the queue of authorized transfers bound to it.
 * Declared automatically by Spring AMQP when the connection is opened.
 */
@Configuration
@EnableConfigurationProperties(RabbitMqProperties.class)
public class RabbitMqTopology {

    @Bean
    public Declarables transferEventsTopology(RabbitMqProperties properties) {
        TopicExchange exchange = new TopicExchange(properties.exchange(), true, false);
        Queue queue = QueueBuilder.durable(properties.queue()).build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(properties.routingKey());
        return new Declarables(exchange, queue, binding);
    }
}
