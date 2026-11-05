package com.iasbank.adapters.repository.event;

import com.iasbank.core.domain.model.EventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface OutboxEventRepository extends ReactiveCrudRepository<OutboxEventEntity, Long> {

    @Query("""
            SELECT * FROM outbox_events
            WHERE status IN ('PENDING', 'RETRYING')
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            """)
    Flux<OutboxEventEntity> findDueForPublication(LocalDateTime now, int limit);

    Flux<OutboxEventEntity> findAllBy(Pageable pageable);

    Flux<OutboxEventEntity> findAllByStatus(EventStatus status, Pageable pageable);

    Mono<Long> countByStatus(EventStatus status);
}
