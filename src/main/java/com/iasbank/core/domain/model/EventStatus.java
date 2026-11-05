package com.iasbank.core.domain.model;

public enum EventStatus {
    /** Stored in the outbox, not attempted yet. */
    PENDING,
    /** A publication attempt failed; it will be retried. */
    RETRYING,
    /** Confirmed by the message broker: the message is in the queue. */
    PUBLISHED,
    /** All retries were exhausted. */
    FAILED
}
