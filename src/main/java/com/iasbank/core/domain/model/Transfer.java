package com.iasbank.core.domain.model;

import com.iasbank.core.domain.errors.BusinessRuleException;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder(toBuilder = true)
public class Transfer {

    Long id;
    String requestReference;
    String sourceAccountId;
    String destinationAccountId;
    BigDecimal amount;
    TransferStatus status;
    RejectionReason rejectionReason;
    LocalDateTime processedAt;

    public void validate() {
        if (amount.signum() <= 0) {
            throw new BusinessRuleException(RejectionReason.INVALID_AMOUNT);
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new BusinessRuleException(RejectionReason.SAME_ACCOUNT);
        }
    }

    /**
     * Two requests are the same when they move the same amount between the same accounts.
     * Amounts are compared by value, so 100000 and 100000.00 are equal.
     */
    public boolean isSameRequestAs(Transfer other) {
        return sourceAccountId.equals(other.sourceAccountId)
                && destinationAccountId.equals(other.destinationAccountId)
                && amount.compareTo(other.amount) == 0;
    }

    public Transfer authorize() {
        return toBuilder()
                .status(TransferStatus.AUTHORIZED)
                .processedAt(LocalDateTime.now())
                .build();
    }

    public Transfer reject(RejectionReason reason) {
        return toBuilder()
                .status(TransferStatus.REJECTED)
                .rejectionReason(reason)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
