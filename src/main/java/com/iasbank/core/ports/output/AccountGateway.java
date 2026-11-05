package com.iasbank.core.ports.output;

import com.iasbank.core.domain.model.Account;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

public interface AccountGateway {

    Mono<Account> save(Account account);

    Mono<Account> findById(Long id);

    Mono<Account> findByAccountNumber(String accountNumber);

    Flux<Account> findAll();

    Mono<Void> deleteById(Long id);

    Mono<Void> lock(Collection<String> accountNumbers);
}
