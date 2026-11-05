package com.iasbank.adapters.repository.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.domain.model.TransferAuthorizedEvent;
import com.iasbank.core.ports.output.EventGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class EventRepositoryAdapter implements EventGateway {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<OutboxEvent> save(OutboxEvent event) {
        return Mono.fromCallable(() -> OutboxEventEntity.fromDomain(event, objectMapper.writeValueAsString(event.getPayload())))
                .flatMap(outboxEventRepository::save)
                .map(this::toDomain);
    }

    @Override
    public Flux<OutboxEvent> findDueForPublication(LocalDateTime now, int limit) {
        return outboxEventRepository.findDueForPublication(now, limit).map(this::toDomain);
    }

    @Override
    public Flux<OutboxEvent> findNewestFirst(EventStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, NEWEST_FIRST);
        Flux<OutboxEventEntity> events = status == null
                ? outboxEventRepository.findAllBy(pageRequest)
                : outboxEventRepository.findAllByStatus(status, pageRequest);
        return events.map(this::toDomain);
    }

    @Override
    public Mono<Long> count(EventStatus status) {
        return status == null ? outboxEventRepository.count() : outboxEventRepository.countByStatus(status);
    }

    private OutboxEvent toDomain(OutboxEventEntity entity) {
        try {
            return entity.toDomain(objectMapper.readValue(entity.getPayload(), TransferAuthorizedEvent.class));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid payload for event " + entity.getEventId(), error);
        }
    }
}
