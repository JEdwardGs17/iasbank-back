package com.iasbank.adapters.repository.transfer;

import com.iasbank.core.domain.errors.DuplicateTransferException;
import com.iasbank.core.domain.model.RejectionReason;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.domain.model.TransferStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferRepositoryAdapter - traducción entre la base de datos y el dominio")
class TransferRepositoryAdapterTest {

    @Mock
    private TransferRepository transferRepository;

    @InjectMocks
    private TransferRepositoryAdapter adapter;

    @Test
    @DisplayName("RF03: la transferencia se guarda y se lee con su estado y su motivo de rechazo")
    void savesAndReadsTheResult() {
        when(transferRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Transfer rejected = transfer().reject(RejectionReason.DAILY_LIMIT_EXCEEDED);

        StepVerifier.create(adapter.save(rejected))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo(TransferStatus.REJECTED);
                    assertThat(saved.getRejectionReason()).isEqualTo(RejectionReason.DAILY_LIMIT_EXCEEDED);
                    assertThat(saved.getProcessedAt()).isEqualTo(rejected.getProcessedAt());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("RF05: una referencia que ya existe en la base de datos se traduce a un error del dominio")
    void translatesDuplicateReference() {
        when(transferRepository.save(any())).thenReturn(Mono.error(new DuplicateKeyException("uk_request_reference")));

        StepVerifier.create(adapter.save(transfer()))
                .expectError(DuplicateTransferException.class)
                .verify();
    }

    @Test
    @DisplayName("RF02: el acumulado diario suma desde las 00:00 del día hasta antes de las 00:00 del día siguiente")
    void sumsTheWholeDay() {
        when(transferRepository.sumAuthorizedAmount(any(), any(), any())).thenReturn(Mono.just(new BigDecimal("600000")));

        StepVerifier.create(adapter.sumAuthorizedAmountOfDay("CTA-1001", LocalDate.of(2026, 11, 4)))
                .expectNext(new BigDecimal("600000"))
                .verifyComplete();

        verify(transferRepository).sumAuthorizedAmount("CTA-1001",
                LocalDateTime.of(2026, 11, 4, 0, 0), LocalDateTime.of(2026, 11, 5, 0, 0));
    }

    @Test
    @DisplayName("RF06: las recientes se piden por página, de la más nueva a la más antigua")
    void recentTransfersNewestFirst() {
        when(transferRepository.findAllBy(any())).thenReturn(Flux.empty());

        StepVerifier.create(adapter.findRecent(2, 10)).verifyComplete();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transferRepository).findAllBy(pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("processedAt"), Sort.Order.desc("id")));
    }

    private static Transfer transfer() {
        return Transfer.builder()
                .requestReference("REF-001")
                .sourceAccountId("CTA-1001")
                .destinationAccountId("CTA-2001")
                .amount(new BigDecimal("600000"))
                .build();
    }
}
