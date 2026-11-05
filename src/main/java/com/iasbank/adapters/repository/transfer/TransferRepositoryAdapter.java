package com.iasbank.adapters.repository.transfer;

import com.iasbank.core.domain.errors.DuplicateTransferException;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.ports.output.TransferGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;

@Repository
@RequiredArgsConstructor
public class TransferRepositoryAdapter implements TransferGateway {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("processedAt"), Sort.Order.desc("id"));

    private final TransferRepository transferRepository;

    @Override
    public Mono<Transfer> save(Transfer transfer) {
        return transferRepository.save(TransferEntity.fromDomain(transfer))
                .map(TransferEntity::toDomain)
                .onErrorMap(DuplicateKeyException.class,
                        duplicate -> new DuplicateTransferException(transfer.getRequestReference()));
    }

    @Override
    public Mono<Transfer> findByRequestReference(String requestReference) {
        return transferRepository.findByRequestReference(requestReference)
                .map(TransferEntity::toDomain);
    }

    @Override
    public Flux<Transfer> findRecent(int page, int size) {
        return transferRepository.findAllBy(PageRequest.of(page, size, NEWEST_FIRST))
                .map(TransferEntity::toDomain);
    }

    @Override
    public Mono<Long> count() {
        return transferRepository.count();
    }

    @Override
    public Mono<BigDecimal> sumAuthorizedAmountOfDay(String sourceAccountId, LocalDate day) {
        return transferRepository.sumAuthorizedAmount(sourceAccountId, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
    }
}
