package com.iasbank.adapters.messaging.rabbitmq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topology where the transfer events are published.
 */
@ConfigurationProperties(prefix = "iasbank.rabbitmq")
public record RabbitMqProperties(String exchange, String queue, String routingKey) {
}
