package com.iasbank.adapters.messaging.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.ports.output.EventPublisherGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.ZoneId;
import java.util.Date;

/**
 * Publishes events with publisher confirms: an event counts as published only when RabbitMQ
 * confirms it. Spring AMQP is blocking, so calls run on the bounded elastic scheduler.
 */
@Component
@RequiredArgsConstructor
public class RabbitMqEventPublisher implements EventPublisherGateway {

    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final RabbitMqProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> publish(OutboxEvent event) {
        return Mono.fromCallable(() -> toMessage(event))
                .doOnNext(message -> rabbitTemplate.invoke(operations -> {
                    operations.send(properties.exchange(), properties.routingKey(), message);
                    operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                    return null;
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Long> queuedMessages() {
        return Mono.fromCallable(() -> {
                    QueueInformation queue = amqpAdmin.getQueueInfo(properties.queue());
                    return queue == null ? 0L : (long) queue.getMessageCount();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> Mono.empty());
    }

    /** The event id travels as message id, so consumers can discard duplicates (at-least-once delivery). */
    private Message toMessage(OutboxEvent event) throws Exception {
        return MessageBuilder.withBody(objectMapper.writeValueAsBytes(event.getPayload()))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(event.getEventId())
                .setType(event.getType().name())
                .setTimestamp(Date.from(event.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()))
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
