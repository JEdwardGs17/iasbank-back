package com.iasbank.adapters.repository.event;

import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.domain.model.EventType;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.domain.model.TransferAuthorizedEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("outbox_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventEntity {

    @Id
    private Long id;

    @Column("event_id")
    private String eventId;

    @Column("event_type")
    private EventType eventType;

    @Column("aggregate_id")
    private String aggregateId;

    /** JSON of the event payload. */
    private String payload;

    private EventStatus status;

    private int attempts;

    @Column("last_error")
    private String lastError;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column("published_at")
    private LocalDateTime publishedAt;

    public static OutboxEventEntity fromDomain(OutboxEvent event, String payloadJson) {
        return OutboxEventEntity.builder()
                .id(event.getId())
                .eventId(event.getEventId())
                .eventType(event.getType())
                .aggregateId(event.getAggregateId())
                .payload(payloadJson)
                .status(event.getStatus())
                .attempts(event.getAttempts())
                .lastError(event.getLastError())
                .createdAt(event.getCreatedAt())
                .nextAttemptAt(event.getNextAttemptAt())
                .publishedAt(event.getPublishedAt())
                .build();
    }

    public OutboxEvent toDomain(TransferAuthorizedEvent payloadEvent) {
        return OutboxEvent.builder()
                .id(id)
                .eventId(eventId)
                .type(eventType)
                .aggregateId(aggregateId)
                .payload(payloadEvent)
                .status(status)
                .attempts(attempts)
                .lastError(lastError)
                .createdAt(createdAt)
                .nextAttemptAt(nextAttemptAt)
                .publishedAt(publishedAt)
                .build();
    }
}
