package com.iasbank.core.ports.output;

import com.iasbank.core.domain.model.Transfer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface TransferGateway {

    Mono<Transfer> save(Transfer transfer);

    Mono<Transfer> findByRequestReference(String requestReference);

    Flux<Transfer> findRecent(int page, int size);

    Mono<Long> count();

    Mono<BigDecimal> sumAuthorizedAmountOfDay(String sourceAccountId, LocalDate day);
}
