package com.iasbank.core.ports.output;

import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.domain.model.OutboxEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Persistence of the outbox events.
 */
public interface EventGateway {

    Mono<OutboxEvent> save(OutboxEvent event);

    /** PENDING or RETRYING events whose next attempt is due, oldest first. */
    Flux<OutboxEvent> findDueForPublication(LocalDateTime now, int limit);

    /** Newest first; {@code status} null means every status. */
    Flux<OutboxEvent> findNewestFirst(EventStatus status, int page, int size);

    /** {@code status} null means every status. */
    Mono<Long> count(EventStatus status);
}
