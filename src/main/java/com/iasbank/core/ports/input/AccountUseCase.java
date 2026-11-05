package com.iasbank.core.ports.input;

import com.iasbank.core.domain.model.Account;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;


public interface AccountUseCase {

    Mono<Account> createAccount(Account account);

    Flux<Account> getAllAccounts();

    Mono<Account> getAccountById(Long id);

    Mono<Account> getAccountByNumber(String accountNumber);

    Mono<Account> deposit(String accountNumber, BigDecimal amount);

    Mono<Account> withdraw(String accountNumber, BigDecimal amount);

    /** Configura el límite diario de la cuenta; aplica a las transferencias siguientes. */
    Mono<Account> updateDailyLimit(String accountNumber, BigDecimal dailyLimit);

    Mono<Void> deleteAccount(Long id);
}
