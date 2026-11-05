package com.iasbank.core.ports.input;

import com.iasbank.core.domain.dto.PageResult;
import com.iasbank.core.domain.dto.TransferResult;
import com.iasbank.core.domain.model.Transfer;
import reactor.core.publisher.Mono;

public interface TransferUseCase {

    Mono<TransferResult> processTransfer(Transfer request);

    Mono<PageResult<Transfer>> getRecentTransfers(int page, int size);

    Mono<Transfer> getTransferByReference(String requestReference);
}
