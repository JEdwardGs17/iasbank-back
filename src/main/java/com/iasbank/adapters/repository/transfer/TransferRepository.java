package com.iasbank.adapters.repository.transfer;

import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface TransferRepository extends ReactiveCrudRepository<TransferEntity, Long> {

    Mono<TransferEntity> findByRequestReference(String requestReference);

    Flux<TransferEntity> findAllBy(Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(amount), 0) FROM transfers
            WHERE source_account_id = :sourceAccountId
              AND status = 'AUTHORIZED'
              AND processed_at >= :from
              AND processed_at < :to
            """)
    Mono<BigDecimal> sumAuthorizedAmount(String sourceAccountId, LocalDateTime from, LocalDateTime to);
}
