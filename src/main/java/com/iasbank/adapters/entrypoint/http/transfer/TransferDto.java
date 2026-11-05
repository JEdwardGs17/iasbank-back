package com.iasbank.adapters.entrypoint.http.transfer;

import com.iasbank.core.domain.model.RejectionReason;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.domain.model.TransferStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * HTTP messages of the transfers API.
 */
public final class TransferDto {

    private TransferDto() {
    }

    public record Request(
            @NotBlank(message = "Request reference is required") String requestReference,
            @NotBlank(message = "Source account is required") String sourceAccountId,
            @NotBlank(message = "Destination account is required") String destinationAccountId,
            @NotNull(message = "Amount is required") BigDecimal amount
    ) {

        public Transfer toDomain() {
            return Transfer.builder()
                    .requestReference(requestReference)
                    .sourceAccountId(sourceAccountId)
                    .destinationAccountId(destinationAccountId)
                    .amount(amount)
                    .build();
        }
    }

    public record Response(
            Long id,
            String requestReference,
            String sourceAccountId,
            String destinationAccountId,
            BigDecimal amount,
            TransferStatus status,
            RejectionReason rejectionReason,
            LocalDateTime processedAt
    ) {

        public static Response fromDomain(Transfer transfer) {
            return new Response(
                    transfer.getId(),
                    transfer.getRequestReference(),
                    transfer.getSourceAccountId(),
                    transfer.getDestinationAccountId(),
                    transfer.getAmount(),
                    transfer.getStatus(),
                    transfer.getRejectionReason(),
                    transfer.getProcessedAt()
            );
        }
    }
}
