package com.iasbank.core.ports.output;

import com.iasbank.core.domain.model.OutboxEvent;
import reactor.core.publisher.Mono;

/**
 * Message broker where the events are published.
 */
public interface EventPublisherGateway {

    /** Completes only when the broker confirms the message; errors otherwise. */
    Mono<Void> publish(OutboxEvent event);

    /** Messages currently in the queue; empty when the broker is not reachable. */
    Mono<Long> queuedMessages();
}
