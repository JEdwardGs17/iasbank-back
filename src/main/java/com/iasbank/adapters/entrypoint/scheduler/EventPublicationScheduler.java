package com.iasbank.adapters.entrypoint.scheduler;

import com.iasbank.core.ports.input.EventUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Entry point that triggers the publication of the outbox events. The next run starts
 * only after the previous one completes (fixed delay).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublicationScheduler {

    private final EventUseCase eventUseCase;

    @Scheduled(fixedDelayString = "${iasbank.events.publication-interval-ms:2000}")
    public Mono<Void> publishPendingEvents() {
        return eventUseCase.publishPendingEvents()
                .onErrorResume(error -> {
                    log.warn("Event publication run failed: {}", error.getMessage());
                    return Mono.empty();
                });
    }
}
