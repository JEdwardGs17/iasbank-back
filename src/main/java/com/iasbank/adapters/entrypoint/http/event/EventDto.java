package com.iasbank.adapters.entrypoint.http.event;

import com.iasbank.core.domain.constants.EventConstants;
import com.iasbank.core.domain.dto.EventSummary;
import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.domain.model.EventType;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.domain.model.TransferAuthorizedEvent;

import java.time.LocalDateTime;

/**
 * HTTP messages of the events administration API.
 */
public final class EventDto {

    private EventDto() {
    }

    public record Response(
            String eventId,
            EventType type,
            String aggregateId,
            EventStatus status,
            int attempts,
            int retries,
            int maxRetries,
            String lastError,
            LocalDateTime createdAt,
            LocalDateTime nextAttemptAt,
            LocalDateTime publishedAt,
            TransferAuthorizedEvent payload
    ) {

        public static Response fromDomain(OutboxEvent event) {
            return new Response(
                    event.getEventId(),
                    event.getType(),
                    event.getAggregateId(),
                    event.getStatus(),
                    event.getAttempts(),
                    event.retries(),
                    EventConstants.MAX_PUBLISH_RETRIES,
                    event.getLastError(),
                    event.getCreatedAt(),
                    event.getNextAttemptAt(),
                    event.getPublishedAt(),
                    event.getPayload()
            );
        }
    }

    /** {@code queuedMessages} is null when RabbitMQ is not reachable. */
    public record Summary(long pending, long retrying, long published, long failed, Long queuedMessages, int maxRetries) {

        public static Summary fromDomain(EventSummary summary) {
            return new Summary(summary.pending(), summary.retrying(), summary.published(), summary.failed(),
                    summary.queuedMessages(), EventConstants.MAX_PUBLISH_RETRIES);
        }
    }
}
