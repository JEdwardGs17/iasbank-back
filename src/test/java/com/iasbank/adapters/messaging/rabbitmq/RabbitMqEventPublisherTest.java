package com.iasbank.adapters.messaging.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.domain.model.Transfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RabbitMqEventPublisher - publicación con confirmación (punto opcional RabbitMQ)")
class RabbitMqEventPublisherTest {

    private static final RabbitMqProperties PROPERTIES =
            new RabbitMqProperties("iasbank.transfers", "iasbank.transfers.authorized", "transfer.authorized");

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private AmqpAdmin amqpAdmin;

    private final RabbitOperations operations = mock(RabbitOperations.class);

    private RabbitMqEventPublisher publisher;

    private final OutboxEvent event = OutboxEvent.transferAuthorized(Transfer.builder()
            .id(7L)
            .requestReference("REF-001")
            .sourceAccountId("CTA-1001")
            .destinationAccountId("CTA-2001")
            .amount(new BigDecimal("600000"))
            .build()
            .authorize());

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher = new RabbitMqEventPublisher(rabbitTemplate, amqpAdmin, PROPERTIES, objectMapper);
    }

    @Test
    @DisplayName("envía al exchange con la routing key un mensaje JSON persistente que lleva el id del evento, y espera la confirmación")
    void publishesAndWaitsForTheConfirmation() {
        givenTheBrokerRuns();

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(operations).send(eq("iasbank.transfers"), eq("transfer.authorized"), message.capture());
        verify(operations).waitForConfirmsOrDie(anyLong());

        MessageProperties properties = message.getValue().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(event.getEventId());
        assertThat(properties.getType()).isEqualTo("TRANSFER_AUTHORIZED");
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(message.getValue().getBody())).contains("\"requestReference\":\"REF-001\"");
    }

    @Test
    @DisplayName("si RabbitMQ no confirma el mensaje, la publicación falla para que el evento se reintente")
    void failsWhenTheBrokerDoesNotConfirm() {
        givenTheBrokerRuns();
        doThrow(new AmqpException("nack")).when(operations).waitForConfirmsOrDie(anyLong());

        StepVerifier.create(publisher.publish(event))
                .expectError(AmqpException.class)
                .verify();
    }

    @Test
    @DisplayName("informa cuántos mensajes hay en la cola")
    void queuedMessages() {
        when(amqpAdmin.getQueueInfo("iasbank.transfers.authorized"))
                .thenReturn(new QueueInformation("iasbank.transfers.authorized", 3, 0));

        StepVerifier.create(publisher.queuedMessages()).expectNext(3L).verifyComplete();
    }

    @Test
    @DisplayName("si RabbitMQ no está disponible, no informa mensajes en cola en lugar de fallar")
    void queuedMessagesWhenTheBrokerIsDown() {
        when(amqpAdmin.getQueueInfo(any())).thenThrow(new AmqpException("Connection refused"));

        StepVerifier.create(publisher.queuedMessages()).verifyComplete();
    }

    @SuppressWarnings("unchecked")
    private void givenTheBrokerRuns() {
        when(rabbitTemplate.invoke(any())).thenAnswer(invocation ->
                ((RabbitOperations.OperationsCallback<Object>) invocation.getArgument(0)).doInRabbit(operations));
    }
}
