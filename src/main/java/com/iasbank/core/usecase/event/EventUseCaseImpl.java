package com.iasbank.core.usecase.event;

import com.iasbank.core.domain.constants.EventConstants;
import com.iasbank.core.domain.dto.EventSummary;
import com.iasbank.core.domain.dto.PageResult;
import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.ports.input.EventUseCase;
import com.iasbank.core.ports.output.EventGateway;
import com.iasbank.core.ports.output.EventPublisherGateway;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;

@RequiredArgsConstructor
public class EventUseCaseImpl implements EventUseCase {

    private final EventGateway eventGateway;
    private final EventPublisherGateway eventPublisherGateway;

    /**
     * Deferred: "now" must be taken on every subscription. A scheduler may invoke this method once
     * and re-subscribe to the same Mono on every run (Spring does so for reactive @Scheduled methods).
     */
    @Override
    public Mono<Void> publishPendingEvents() {
        return Flux.defer(() -> eventGateway.findDueForPublication(LocalDateTime.now(), EventConstants.PUBLICATION_BATCH_SIZE))
                .concatMap(this::publish)
                .then();
    }

    @Override
    public Mono<PageResult<OutboxEvent>> getEvents(EventStatus status, int page, int size) {
        return Mono.zip(eventGateway.findNewestFirst(status, page, size).collectList(), eventGateway.count(status))
                .map(result -> new PageResult<>(result.getT1(), page, size, result.getT2()));
    }

    @Override
    public Mono<EventSummary> getEventSummary() {
        return Mono.zip(
                        eventGateway.count(EventStatus.PENDING),
                        eventGateway.count(EventStatus.RETRYING),
                        eventGateway.count(EventStatus.PUBLISHED),
                        eventGateway.count(EventStatus.FAILED),
                        eventPublisherGateway.queuedMessages().map(Optional::of).defaultIfEmpty(Optional.empty()))
                .map(counts -> new EventSummary(
                        counts.getT1(), counts.getT2(), counts.getT3(), counts.getT4(), counts.getT5().orElse(null)));
    }

    /** The outcome of every attempt is stored, so the administration view shows the real state. */
    private Mono<OutboxEvent> publish(OutboxEvent event) {
        return eventPublisherGateway.publish(event)
                .then(Mono.fromSupplier(() -> event.markPublished(LocalDateTime.now())))
                .onErrorResume(error -> Mono.just(event.registerFailedAttempt(error.getMessage(), LocalDateTime.now())))
                .flatMap(eventGateway::save);
    }
}
