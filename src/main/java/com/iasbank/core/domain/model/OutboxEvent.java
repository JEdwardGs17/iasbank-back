package com.iasbank.core.domain.model;

import com.iasbank.core.domain.constants.EventConstants;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An event waiting to be (or already) published to the message broker.
 * It is stored in the same transaction as the business change (transactional outbox),
 * so an event is never lost nor published for a change that was rolled back.
 */
@Value
@Builder(toBuilder = true)
public class OutboxEvent {

    private static final int MAX_ERROR_LENGTH = 500;

    Long id;
    String eventId;
    EventType type;
    String aggregateId;
    TransferAuthorizedEvent payload;
    EventStatus status;
    int attempts;
    String lastError;
    LocalDateTime createdAt;
    LocalDateTime nextAttemptAt;
    LocalDateTime publishedAt;

    public static OutboxEvent transferAuthorized(Transfer transfer) {
        LocalDateTime now = LocalDateTime.now();
        return OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .type(EventType.TRANSFER_AUTHORIZED)
                .aggregateId(transfer.getRequestReference())
                .payload(TransferAuthorizedEvent.from(transfer))
                .status(EventStatus.PENDING)
                .attempts(0)
                .createdAt(now)
                .nextAttemptAt(now)
                .build();
    }

    public OutboxEvent markPublished(LocalDateTime now) {
        return toBuilder()
                .attempts(attempts + 1)
                .status(EventStatus.PUBLISHED)
                .publishedAt(now)
                .nextAttemptAt(null)
                .lastError(null)
                .build();
    }

    /**
     * After the first attempt and {@link EventConstants#MAX_PUBLISH_RETRIES} retries, the event is FAILED.
     * Otherwise it is retried with an exponential delay.
     */
    public OutboxEvent registerFailedAttempt(String error, LocalDateTime now) {
        int attemptsMade = attempts + 1;
        boolean retriesExhausted = attemptsMade > EventConstants.MAX_PUBLISH_RETRIES;
        return toBuilder()
                .attempts(attemptsMade)
                .status(retriesExhausted ? EventStatus.FAILED : EventStatus.RETRYING)
                .nextAttemptAt(retriesExhausted ? null : now.plus(EventConstants.RETRY_BASE_DELAY.multipliedBy(1L << (attemptsMade - 1))))
                .lastError(truncate(error))
                .build();
    }

    /** Retries performed: every attempt after the first one. */
    public int retries() {
        return Math.max(0, attempts - 1);
    }

    private static String truncate(String error) {
        String message = error == null || error.isBlank() ? "Unknown error" : error;
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
