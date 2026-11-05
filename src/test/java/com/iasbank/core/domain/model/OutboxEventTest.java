package com.iasbank.core.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxEvent - ciclo de vida de un evento (punto opcional RabbitMQ)")
class OutboxEventTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0);

    private final OutboxEvent pending = OutboxEvent.transferAuthorized(authorizedTransfer());

    @Test
    @DisplayName("una transferencia autorizada genera un evento PENDING listo para publicarse, con su información")
    void createdFromAuthorizedTransfer() {
        assertThat(pending.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(pending.getType()).isEqualTo(EventType.TRANSFER_AUTHORIZED);
        assertThat(pending.getEventId()).hasSize(36);
        assertThat(pending.getAggregateId()).isEqualTo("REF-001");
        assertThat(pending.getAttempts()).isZero();
        assertThat(pending.getNextAttemptAt()).isNotNull();
        assertThat(pending.getPayload().amount()).isEqualByComparingTo("600000");
        assertThat(pending.getPayload().transferId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("al publicarse queda PUBLISHED, con fecha de publicación y sin próximo intento")
    void published() {
        OutboxEvent published = pending.markPublished(NOW);

        assertThat(published.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isEqualTo(NOW);
        assertThat(published.getNextAttemptAt()).isNull();
        assertThat(published.retries()).isZero();
    }

    @Test
    @DisplayName("cada falla programa un reintento con espera creciente (5 s, 10 s, 20 s) y al agotar 3 reintentos queda FAILED")
    void retriesAndFails() {
        OutboxEvent first = pending.registerFailedAttempt("Connection refused", NOW);
        assertThat(first.getStatus()).isEqualTo(EventStatus.RETRYING);
        assertThat(first.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(first.retries()).isZero();

        OutboxEvent second = first.registerFailedAttempt("Connection refused", NOW);
        assertThat(second.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(second.retries()).isEqualTo(1);

        OutboxEvent third = second.registerFailedAttempt("Connection refused", NOW);
        assertThat(third.getStatus()).isEqualTo(EventStatus.RETRYING);
        assertThat(third.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(20));

        OutboxEvent failed = third.registerFailedAttempt("Connection refused", NOW);
        assertThat(failed.getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(failed.retries()).as("primer intento + 3 reintentos").isEqualTo(3);
        assertThat(failed.getNextAttemptAt()).isNull();
        assertThat(failed.getLastError()).isEqualTo("Connection refused");
    }

    @Test
    @DisplayName("un evento que se publica en un reintento conserva cuántos reintentos necesitó")
    void publishedAfterRetries() {
        OutboxEvent published = pending.registerFailedAttempt("timeout", NOW)
                .registerFailedAttempt("timeout", NOW)
                .markPublished(NOW);

        assertThat(published.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(published.retries()).isEqualTo(2);
        assertThat(published.getLastError()).isNull();
    }

    private static Transfer authorizedTransfer() {
        return Transfer.builder()
                .id(7L)
                .requestReference("REF-001")
                .sourceAccountId("CTA-1001")
                .destinationAccountId("CTA-2001")
                .amount(new BigDecimal("600000"))
                .build()
                .authorize();
    }
}
