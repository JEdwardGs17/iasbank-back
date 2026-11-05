package com.iasbank.config;

import com.iasbank.adapters.repository.account.AccountEntity;
import com.iasbank.adapters.messaging.rabbitmq.RabbitMqProperties;
import com.iasbank.adapters.repository.account.AccountRepository;
import com.iasbank.adapters.repository.event.OutboxEventRepository;
import com.iasbank.adapters.repository.transfer.TransferRepository;
import com.iasbank.core.domain.model.AccountType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

/**
 * Starts every run as a clean day, as the exercise assumes: no transfers and the seed
 * accounts with their initial balance. Balances are restored together with the transfers so
 * the accounts always match an empty transfer history. The events of those transfers (outbox
 * and queue) are cleared too. Daily limits are configurable, so an existing account keeps
 * its configured limit; a missing seed account is created with the default limit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    static final List<String> SEED_ACCOUNTS = List.of("CTA-1001", "CTA-1002", "CTA-2001");
    static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000000");
    static final BigDecimal DAILY_LIMIT = new BigDecimal("5000000");

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AmqpAdmin amqpAdmin;
    private final RabbitMqProperties rabbitMqProperties;

    @Override
    public void run(ApplicationArguments args) {
        outboxEventRepository.deleteAll()
                .then(transferRepository.deleteAll())
                .thenMany(Flux.fromIterable(SEED_ACCOUNTS).concatMap(this::restoreSeedAccount))
                .then()
                .doOnSuccess(done -> log.info("Clean day: transfers cleared and seed accounts {} restored", SEED_ACCOUNTS))
                .block();
        purgeEventQueue();
    }

    /** The broker may be down at startup: the application must start anyway. */
    private void purgeEventQueue() {
        try {
            amqpAdmin.purgeQueue(rabbitMqProperties.queue(), false);
        } catch (RuntimeException error) {
            log.warn("Could not purge queue {}: {}", rabbitMqProperties.queue(), error.getMessage());
        }
    }

    private Mono<AccountEntity> restoreSeedAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .switchIfEmpty(Mono.fromSupplier(() -> newSeedAccount(accountNumber)))
                .flatMap(account -> {
                    account.setBalance(INITIAL_BALANCE);
                    return accountRepository.save(account);
                });
    }

    private static AccountEntity newSeedAccount(String accountNumber) {
        AccountEntity account = new AccountEntity();
        account.setAccountNumber(accountNumber);
        account.setHolder("Account Holder " + accountNumber.substring(4));
        account.setAccountType(AccountType.SAVINGS);
        account.setDailyLimit(DAILY_LIMIT);
        return account;
    }
}
