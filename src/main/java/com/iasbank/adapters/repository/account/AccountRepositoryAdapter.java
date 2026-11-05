package com.iasbank.adapters.repository.account;

import com.iasbank.core.domain.model.Account;
import com.iasbank.core.ports.output.AccountGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

@Repository
@RequiredArgsConstructor
public class AccountRepositoryAdapter implements AccountGateway {

    private final AccountRepository accountRepository;

    @Override
    public Mono<Account> save(Account account) {
        return accountRepository.save(AccountEntity.fromDomain(account))
                .map(AccountEntity::toDomain);
    }

    @Override
    public Mono<Account> findById(Long id) {
        return accountRepository.findById(id)
                .map(AccountEntity::toDomain);
    }

    @Override
    public Mono<Account> findByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(AccountEntity::toDomain);
    }

    @Override
    public Flux<Account> findAll() {
        return accountRepository.findAll()
                .map(AccountEntity::toDomain);
    }

    @Override
    public Mono<Void> deleteById(Long id) {
        return accountRepository.deleteById(id);
    }

    @Override
    public Mono<Void> lock(Collection<String> accountNumbers) {
        return accountRepository.lockByAccountNumbers(accountNumbers).then();
    }
}
