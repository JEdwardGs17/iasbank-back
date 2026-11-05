package com.iasbank.core.usecase.account;

import com.iasbank.core.domain.model.Account;
import com.iasbank.core.ports.input.AccountUseCase;
import com.iasbank.core.ports.output.AccountGateway;
import com.iasbank.core.ports.output.TransactionGateway;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

@RequiredArgsConstructor
public class AccountUseCaseImpl implements AccountUseCase {

    private final AccountGateway accountGateway;
    private final TransactionGateway transactionGateway;

    @Override
    public Mono<Account> createAccount(Account account) {
        return accountGateway.findByAccountNumber(account.getAccountNumber())
                .flatMap(existing -> Mono.<Account>error(
                        new IllegalArgumentException("Account already exists with number: " + account.getAccountNumber())))
                .switchIfEmpty(Mono.defer(() -> accountGateway.save(account)));
    }

    @Override
    public Flux<Account> getAllAccounts() {
        return accountGateway.findAll();
    }

    @Override
    public Mono<Account> getAccountById(Long id) {
        return accountGateway.findById(id);
    }

    @Override
    public Mono<Account> getAccountByNumber(String accountNumber) {
        return accountGateway.findByAccountNumber(accountNumber);
    }

    @Override
    public Mono<Account> deposit(String accountNumber, BigDecimal amount) {
        return findExisting(accountNumber)
                .flatMap(account -> {
                    account.deposit(amount);
                    return accountGateway.save(account);
                });
    }

    @Override
    public Mono<Account> withdraw(String accountNumber, BigDecimal amount) {
        return findExisting(accountNumber)
                .flatMap(account -> {
                    account.withdraw(amount);
                    return accountGateway.save(account);
                });
    }

    /**
     * The account is locked as in transfer processing (RF04), so the change never overwrites
     * the balance of a transfer that is being processed at the same time.
     */
    @Override
    public Mono<Account> updateDailyLimit(String accountNumber, BigDecimal dailyLimit) {
        Mono<Account> updated = accountGateway.lock(List.of(accountNumber))
                .then(findExisting(accountNumber))
                .flatMap(account -> {
                    account.changeDailyLimit(dailyLimit);
                    return accountGateway.save(account);
                });
        return transactionGateway.inTransaction(updated);
    }

    @Override
    public Mono<Void> deleteAccount(Long id) {
        return accountGateway.findById(id)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Account not found with id: " + id)))
                .flatMap(account -> accountGateway.deleteById(id));
    }

    private Mono<Account> findExisting(String accountNumber) {
        return accountGateway.findByAccountNumber(accountNumber)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Account not found: " + accountNumber)));
    }
}
