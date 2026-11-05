package com.iasbank.core.ports.output;

import reactor.core.publisher.Mono;

public interface TransactionGateway {

    <T> Mono<T> inTransaction(Mono<T> operation);
}
