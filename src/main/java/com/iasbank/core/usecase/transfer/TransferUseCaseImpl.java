package com.iasbank.core.usecase.transfer;

import com.iasbank.core.domain.dto.PageResult;
import com.iasbank.core.domain.dto.TransferResult;
import com.iasbank.core.domain.errors.BusinessRuleException;
import com.iasbank.core.domain.errors.DuplicateTransferException;
import com.iasbank.core.domain.errors.RequestReferenceConflictException;
import com.iasbank.core.domain.model.Account;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.domain.model.RejectionReason;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.ports.input.TransferUseCase;
import com.iasbank.core.ports.output.AccountGateway;
import com.iasbank.core.ports.output.EventGateway;
import com.iasbank.core.ports.output.TransactionGateway;
import com.iasbank.core.ports.output.TransferGateway;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
public class TransferUseCaseImpl implements TransferUseCase {

    private final TransferGateway transferGateway;
    private final AccountGateway accountGateway;
    private final TransactionGateway transactionGateway;
    private final EventGateway eventGateway;

    @Override
    public Mono<TransferResult> processTransfer(Transfer request) {
        Mono<TransferResult> result = lockAccounts(request)
                .then(findOriginal(request))
                .switchIfEmpty(Mono.defer(() -> process(request).map(TransferResult::processed)));

        return transactionGateway.inTransaction(result)
                .onErrorResume(DuplicateTransferException.class, duplicate -> findOriginal(request));
    }

    @Override
    public Mono<PageResult<Transfer>> getRecentTransfers(int page, int size) {
        return Mono.zip(transferGateway.findRecent(page, size).collectList(), transferGateway.count())
                .map(result -> new PageResult<>(result.getT1(), page, size, result.getT2()));
    }

    @Override
    public Mono<Transfer> getTransferByReference(String requestReference) {
        return transferGateway.findByRequestReference(requestReference);
    }

    private Mono<Void> lockAccounts(Transfer request) {
        return accountGateway.lock(List.of(request.getSourceAccountId(), request.getDestinationAccountId()));
    }

    private Mono<TransferResult> findOriginal(Transfer request) {
        return transferGateway.findByRequestReference(request.getRequestReference())
                .map(original -> {
                    if (!original.isSameRequestAs(request)) {
                        throw new RequestReferenceConflictException(request.getRequestReference());
                    }
                    return TransferResult.replayed(original);
                });
    }

    private Mono<Transfer> process(Transfer request) {
        return Mono.just(request)
                .doOnNext(Transfer::validate)
                .flatMap(transfer -> Mono.zip(
                        findAccount(transfer.getSourceAccountId()),
                        findAccount(transfer.getDestinationAccountId())))
                .flatMap(accounts -> authorize(request, accounts.getT1(), accounts.getT2()))
                .onErrorResume(BusinessRuleException.class,
                        rejection -> transferGateway.save(request.reject(rejection.getReason())));
    }

    private Mono<Transfer> authorize(Transfer transfer, Account source, Account destination) {
        return transferGateway.sumAuthorizedAmountOfDay(source.getAccountNumber(), LocalDate.now())
                .flatMap(authorizedToday -> {
                    source.validateDailyLimit(authorizedToday, transfer.getAmount());
                    source.withdraw(transfer.getAmount());
                    destination.deposit(transfer.getAmount());
                    return accountGateway.save(source)
                            .then(accountGateway.save(destination))
                            .then(transferGateway.save(transfer.authorize()))
                            .flatMap(this::registerAuthorizedEvent);
                });
    }

    /** Optional point (RabbitMQ): the event is stored in the same transaction as the transfer (outbox). */
    private Mono<Transfer> registerAuthorizedEvent(Transfer authorized) {
        return eventGateway.save(OutboxEvent.transferAuthorized(authorized)).thenReturn(authorized);
    }

    private Mono<Account> findAccount(String accountNumber) {
        return accountGateway.findByAccountNumber(accountNumber)
                .switchIfEmpty(Mono.error(() -> new BusinessRuleException(RejectionReason.ACCOUNT_NOT_FOUND)));
    }
}
