package com.iasbank.core.usecase.transfer;

import com.iasbank.core.domain.dto.TransferResult;
import com.iasbank.core.domain.errors.DuplicateTransferException;
import com.iasbank.core.domain.errors.RequestReferenceConflictException;
import com.iasbank.core.domain.model.Account;
import com.iasbank.core.domain.model.AccountType;
import com.iasbank.core.domain.model.RejectionReason;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.domain.model.TransferStatus;
import com.iasbank.core.domain.model.EventType;
import com.iasbank.core.ports.output.AccountGateway;
import com.iasbank.core.ports.output.EventGateway;
import com.iasbank.core.ports.output.TransactionGateway;
import com.iasbank.core.ports.output.TransferGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferUseCaseImplTest {

    @Mock
    private TransferGateway transferGateway;

    @Mock
    private AccountGateway accountGateway;

    @Mock
    private TransactionGateway transactionGateway;

    @Mock
    private EventGateway eventGateway;

    @InjectMocks
    private TransferUseCaseImpl transferUseCase;

    @BeforeEach
    void setUp() {
        lenient().when(transactionGateway.inTransaction(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accountGateway.lock(any())).thenReturn(Mono.empty());
        lenient().when(eventGateway.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    // ---------- RF01 ----------

    @Test
    void authorizesTransferAndRecordsProcessingTime() {
        Account source = account("CTA-1001", "1000000");
        Account destination = account("CTA-2001", "0");
        givenNewReference();
        givenAccounts(source, destination);
        givenAuthorizedToday("CTA-1001", "0");
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-2001", "600000")))
                .assertNext(result -> {
                    assertThat(result.replayed()).isFalse();
                    assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.AUTHORIZED);
                    assertThat(result.transfer().getRejectionReason()).isNull();
                    assertThat(result.transfer().getProcessedAt()).isNotNull();
                })
                .verifyComplete();

        assertThat(source.getBalance()).isEqualByComparingTo("400000");
        assertThat(destination.getBalance()).isEqualByComparingTo("600000");
    }

    // ---------- Punto opcional: eventos ----------

    @Test
    void registersAnEventForAnAuthorizedTransfer() {
        givenNewReference();
        givenAccounts(account("CTA-1001", "1000000"), account("CTA-2001", "0"));
        givenAuthorizedToday("CTA-1001", "0");
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-2001", "600000")))
                .expectNextCount(1)
                .verifyComplete();

        verify(eventGateway).save(argThat(event -> event.getType() == EventType.TRANSFER_AUTHORIZED
                && event.getAggregateId().equals("REF-001")));
    }

    @Test
    void doesNotRegisterAnEventForARejectedTransfer() {
        givenNewReference();
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-1001", "600000")))
                .expectNextCount(1)
                .verifyComplete();

        verify(eventGateway, never()).save(any());
    }

    @Test
    void doesNotRegisterAnotherEventForARepeatedReference() {
        when(transferGateway.findByRequestReference("REF-001"))
                .thenReturn(Mono.just(processed(request("CTA-1001", "CTA-2001", "600000"))));

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-2001", "600000")))
                .expectNextCount(1)
                .verifyComplete();

        verify(eventGateway, never()).save(any());
    }

    // ---------- RF02 / RF03 ----------

    @Test
    void rejectsTransferWithAmountNotGreaterThanZero() {
        givenNewReference();
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-2001", "0")))
                .assertNext(result -> assertRejected(result, RejectionReason.INVALID_AMOUNT))
                .verifyComplete();
    }

    @Test
    void rejectsTransferToSameAccount() {
        givenNewReference();
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-1001", "100000")))
                .assertNext(result -> assertRejected(result, RejectionReason.SAME_ACCOUNT))
                .verifyComplete();
    }

    @Test
    void rejectsTransferWhenAccountDoesNotExist() {
        givenNewReference();
        givenSavesReturnInput();
        when(accountGateway.findByAccountNumber("CTA-9999")).thenReturn(Mono.empty());
        when(accountGateway.findByAccountNumber("CTA-2001")).thenReturn(Mono.just(account("CTA-2001", "0")));

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-9999", "CTA-2001", "100000")))
                .assertNext(result -> assertRejected(result, RejectionReason.ACCOUNT_NOT_FOUND))
                .verifyComplete();
    }

    @Test
    void rejectsTransferThatExceedsDailyLimit() {
        givenNewReference();
        givenAccounts(account("CTA-1001", "10000000"), account("CTA-2001", "0"));
        givenAuthorizedToday("CTA-1001", "4500000");
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-2001", "600000")))
                .assertNext(result -> assertRejected(result, RejectionReason.DAILY_LIMIT_EXCEEDED))
                .verifyComplete();

        verify(accountGateway, never()).save(any());
    }

    @Test
    void authorizesTransferThatReachesExactlyTheDailyLimit() {
        givenNewReference();
        givenAccounts(account("CTA-1001", "10000000"), account("CTA-2001", "0"));
        givenAuthorizedToday("CTA-1001", "4400000");
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-2001", "600000")))
                .assertNext(result -> assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.AUTHORIZED))
                .verifyComplete();
    }

    @Test
    void rejectsTransferWithInsufficientBalance() {
        givenNewReference();
        givenAccounts(account("CTA-1001", "100"), account("CTA-2001", "0"));
        givenAuthorizedToday("CTA-1001", "0");
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-2001", "600000")))
                .assertNext(result -> assertRejected(result, RejectionReason.INSUFFICIENT_BALANCE))
                .verifyComplete();

        verify(accountGateway, never()).save(any());
    }

    // ---------- RF04 ----------

    @Test
    void locksBothAccountsBeforeCheckingTheRequest() {
        givenNewReference();
        givenSavesReturnInput();

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-1001", "100000")))
                .expectNextCount(1)
                .verifyComplete();

        InOrder order = inOrder(accountGateway, transferGateway);
        order.verify(accountGateway).lock(List.of("CTA-1001", "CTA-1001"));
        order.verify(transferGateway).findByRequestReference("REF-001");
    }

    @Test
    void returnsWinnerResultWhenSameReferenceIsProcessedConcurrently() {
        Transfer winner = request("CTA-1001", "CTA-1001", "100000").reject(RejectionReason.SAME_ACCOUNT);
        when(transferGateway.findByRequestReference("REF-001"))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(winner));
        when(transferGateway.save(any())).thenReturn(Mono.error(new DuplicateTransferException("REF-001")));

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-1001", "100000")))
                .assertNext(result -> {
                    assertThat(result.replayed()).isTrue();
                    assertThat(result.transfer()).isEqualTo(winner);
                })
                .verifyComplete();
    }

    // ---------- RF05 ----------

    @Test
    void returnsOriginalResultWhenReferenceIsRepeatedWithSameData() {
        Transfer original = processed(request("CTA-1001", "CTA-2001", "600000.00"));
        when(transferGateway.findByRequestReference("REF-001")).thenReturn(Mono.just(original));

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-2001", "600000")))
                .assertNext(result -> {
                    assertThat(result.replayed()).isTrue();
                    assertThat(result.transfer()).isEqualTo(original);
                })
                .verifyComplete();

        verify(transferGateway, never()).save(any());
        verify(transferGateway, never()).sumAuthorizedAmountOfDay(any(), any());
    }

    @Test
    void rejectsRepeatedReferenceWithDifferentData() {
        Transfer original = processed(request("CTA-1001", "CTA-2001", "600000"));
        when(transferGateway.findByRequestReference("REF-001")).thenReturn(Mono.just(original));

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-1002", "900000")))
                .expectError(RequestReferenceConflictException.class)
                .verify();

        verify(transferGateway, never()).save(any());
        verify(accountGateway, never()).save(any());
    }

    @Test
    void rejectsConcurrentRepeatedReferenceWithDifferentData() {
        Transfer winner = processed(request("CTA-1001", "CTA-2001", "600000"));
        when(transferGateway.findByRequestReference("REF-001"))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(winner));
        when(transferGateway.save(any())).thenReturn(Mono.error(new DuplicateTransferException("REF-001")));

        StepVerifier.create(transferUseCase.processTransfer(request("CTA-1001", "CTA-1001", "900000")))
                .expectError(RequestReferenceConflictException.class)
                .verify();
    }

    // ---------- RF06 ----------

    @Test
    void returnsRequestedPageOfRecentTransfersWithPaginationMetadata() {
        Transfer first = Transfer.builder().requestReference("REF-003").build();
        Transfer second = Transfer.builder().requestReference("REF-002").build();
        when(transferGateway.findRecent(1, 2)).thenReturn(Flux.just(first, second));
        when(transferGateway.count()).thenReturn(Mono.just(5L));

        StepVerifier.create(transferUseCase.getRecentTransfers(1, 2))
                .assertNext(page -> {
                    assertThat(page.content()).containsExactly(first, second);
                    assertThat(page.page()).isEqualTo(1);
                    assertThat(page.size()).isEqualTo(2);
                    assertThat(page.totalElements()).isEqualTo(5);
                    assertThat(page.totalPages()).isEqualTo(3);
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyPageWhenThereAreNoTransfers() {
        when(transferGateway.findRecent(0, 20)).thenReturn(Flux.empty());
        when(transferGateway.count()).thenReturn(Mono.just(0L));

        StepVerifier.create(transferUseCase.getRecentTransfers(0, 20))
                .assertNext(page -> {
                    assertThat(page.content()).isEmpty();
                    assertThat(page.totalPages()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void findsTransferByReference() {
        Transfer original = processed(request("CTA-1001", "CTA-2001", "600000"));
        when(transferGateway.findByRequestReference("REF-001")).thenReturn(Mono.just(original));

        StepVerifier.create(transferUseCase.getTransferByReference("REF-001"))
                .expectNext(original)
                .verifyComplete();
    }

    // ---------- helpers ----------

    private static void assertRejected(TransferResult result, RejectionReason reason) {
        assertThat(result.replayed()).isFalse();
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(result.transfer().getRejectionReason()).isEqualTo(reason);
        assertThat(result.transfer().getProcessedAt()).isNotNull();
    }

    private void givenNewReference() {
        when(transferGateway.findByRequestReference("REF-001")).thenReturn(Mono.empty());
    }

    private void givenAccounts(Account source, Account destination) {
        when(accountGateway.findByAccountNumber(source.getAccountNumber())).thenReturn(Mono.just(source));
        when(accountGateway.findByAccountNumber(destination.getAccountNumber())).thenReturn(Mono.just(destination));
    }

    private void givenAuthorizedToday(String accountNumber, String amount) {
        when(transferGateway.sumAuthorizedAmountOfDay(eq(accountNumber), any(LocalDate.class)))
                .thenReturn(Mono.just(new BigDecimal(amount)));
    }

    private void givenSavesReturnInput() {
        when(transferGateway.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(accountGateway.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private static Transfer processed(Transfer request) {
        return request.toBuilder().id(1L).build().authorize();
    }

    private static Transfer request(String source, String destination, String amount) {
        return Transfer.builder()
                .requestReference("REF-001")
                .sourceAccountId(source)
                .destinationAccountId(destination)
                .amount(new BigDecimal(amount))
                .build();
    }

    private static Account account(String number, String balance) {
        return new Account(null, number, "Holder " + number, new BigDecimal(balance),
                AccountType.SAVINGS, new BigDecimal("5000000"));
    }
}
