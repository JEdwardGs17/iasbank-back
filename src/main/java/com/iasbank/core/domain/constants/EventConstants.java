package com.iasbank.core.domain.constants;

import java.time.Duration;

/**
 * Publication policy for the events of the outbox.
 */
public final class EventConstants {

    /** Retries after the first failed publication attempt. */
    public static final int MAX_PUBLISH_RETRIES = 3;

    /** Wait before the first retry; it doubles on each retry (5 s, 10 s, 20 s). */
    public static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(5);

    /** Events published per scheduler run. */
    public static final int PUBLICATION_BATCH_SIZE = 50;

    private EventConstants() {
    }
}
