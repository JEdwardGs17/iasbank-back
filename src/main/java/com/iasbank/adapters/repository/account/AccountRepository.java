package com.iasbank.adapters.repository.account;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

public interface AccountRepository extends ReactiveCrudRepository<AccountEntity, Long> {

    Mono<AccountEntity> findByAccountNumber(String accountNumber);

    /**
     * Rows are locked in account number order so two transfers between the
     * same accounts in opposite directions can never deadlock.
     */
    @Query("SELECT id FROM accounts WHERE account_number IN (:accountNumbers) ORDER BY account_number FOR UPDATE")
    Flux<Long> lockByAccountNumbers(Collection<String> accountNumbers);
}
