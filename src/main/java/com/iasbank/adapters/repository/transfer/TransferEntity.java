package com.iasbank.adapters.repository.transfer;

import com.iasbank.core.domain.model.RejectionReason;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.domain.model.TransferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table("transfers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferEntity {

    @Id
    private Long id;

    @Column("request_reference")
    private String requestReference;

    @Column("source_account_id")
    private String sourceAccountId;

    @Column("destination_account_id")
    private String destinationAccountId;

    private BigDecimal amount;

    private TransferStatus status;

    @Column("rejection_reason")
    private RejectionReason rejectionReason;

    @Column("processed_at")
    private LocalDateTime processedAt;

    public static TransferEntity fromDomain(Transfer transfer) {
        return TransferEntity.builder()
                .id(transfer.getId())
                .requestReference(transfer.getRequestReference())
                .sourceAccountId(transfer.getSourceAccountId())
                .destinationAccountId(transfer.getDestinationAccountId())
                .amount(transfer.getAmount())
                .status(transfer.getStatus())
                .rejectionReason(transfer.getRejectionReason())
                .processedAt(transfer.getProcessedAt())
                .build();
    }

    public Transfer toDomain() {
        return Transfer.builder()
                .id(id)
                .requestReference(requestReference)
                .sourceAccountId(sourceAccountId)
                .destinationAccountId(destinationAccountId)
                .amount(amount)
                .status(status)
                .rejectionReason(rejectionReason)
                .processedAt(processedAt)
                .build();
    }
}
