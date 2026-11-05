package com.iasbank.adapters.repository;

import com.iasbank.core.ports.output.TransactionGateway;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

/**
 * READ COMMITTED: every read inside the transaction sees the latest committed data,
 * so the daily total computed after acquiring the account lock is always up to date.
 */
@Component
public class TransactionAdapter implements TransactionGateway {

    private final TransactionalOperator transactionalOperator;

    public TransactionAdapter(ReactiveTransactionManager transactionManager) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionalOperator = TransactionalOperator.create(transactionManager, definition);
    }

    @Override
    public <T> Mono<T> inTransaction(Mono<T> operation) {
        return transactionalOperator.transactional(operation);
    }
}
