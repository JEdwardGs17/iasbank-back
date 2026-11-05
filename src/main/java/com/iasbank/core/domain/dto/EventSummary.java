package com.iasbank.core.domain.dto;

/**
 * Events by status, plus the messages currently in the broker queue
 * ({@code queuedMessages} is null when the broker is not reachable).
 */
public record EventSummary(long pending, long retrying, long published, long failed, Long queuedMessages) {
}
