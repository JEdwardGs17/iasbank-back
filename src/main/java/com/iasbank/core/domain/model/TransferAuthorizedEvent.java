package com.iasbank.core.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Business fact published when a transfer is authorized, so other systems can react to it.
 */
public record TransferAuthorizedEvent(
        Long transferId,
        String requestReference,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal amount,
        LocalDateTime processedAt
) {

    public static TransferAuthorizedEvent from(Transfer transfer) {
        return new TransferAuthorizedEvent(
                transfer.getId(),
                transfer.getRequestReference(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                transfer.getAmount(),
                transfer.getProcessedAt()
        );
    }
}
