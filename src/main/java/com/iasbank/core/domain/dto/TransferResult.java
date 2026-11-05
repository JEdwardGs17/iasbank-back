package com.iasbank.core.domain.dto;

import com.iasbank.core.domain.model.Transfer;

/**
 * Outcome of a transfer request: processed now, or the original result of a repeated reference.
 */
public record TransferResult(Transfer transfer, boolean replayed) {

    public static TransferResult processed(Transfer transfer) {
        return new TransferResult(transfer, false);
    }

    public static TransferResult replayed(Transfer transfer) {
        return new TransferResult(transfer, true);
    }
}
