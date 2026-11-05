package com.iasbank.core.usecase.account;

import com.iasbank.core.domain.errors.BusinessRuleException;
import com.iasbank.core.domain.model.Account;
import com.iasbank.core.domain.model.AccountType;
import com.iasbank.core.domain.model.RejectionReason;
import com.iasbank.core.ports.output.AccountGateway;
import com.iasbank.core.ports.output.TransactionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountUseCaseImplTest {

    @Mock
    private AccountGateway accountGateway;

    @Mock
    private TransactionGateway transactionGateway;

    @InjectMocks
    private AccountUseCaseImpl accountUseCase;

    @BeforeEach
    void setUp() {
        lenient().when(transactionGateway.inTransaction(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accountGateway.lock(any())).thenReturn(Mono.empty());
        lenient().when(accountGateway.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    // ---------- Creación ----------

    @Test
    void createsANewAccount() {
        when(accountGateway.findByAccountNumber("CTA-3001")).thenReturn(Mono.empty());

        StepVerifier.create(accountUseCase.createAccount(account("CTA-3001", "500000")))
                .assertNext(account -> assertThat(account.getAccountNumber()).isEqualTo("CTA-3001"))
                .verifyComplete();

        verify(accountGateway).save(any());
    }

    @Test
    void rejectsAnAccountNumberThatAlreadyExists() {
        when(accountGateway.findByAccountNumber("CTA-1001")).thenReturn(Mono.just(account("CTA-1001", "10000000")));

        StepVerifier.create(accountUseCase.createAccount(account("CTA-1001", "500000")))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(accountGateway, never()).save(any());
    }

    // ---------- Depósitos y retiros ----------

    @Test
    void depositAddsTheAmountToTheBalance() {
        when(accountGateway.findByAccountNumber("CTA-1001")).thenReturn(Mono.just(account("CTA-1001", "1000000")));

        StepVerifier.create(accountUseCase.deposit("CTA-1001", new BigDecimal("250000")))
                .assertNext(account -> assertThat(account.getBalance()).isEqualByComparingTo("1250000"))
                .verifyComplete();
    }

    @Test
    void withdrawSubtractsTheAmountFromTheBalance() {
        when(accountGateway.findByAccountNumber("CTA-1001")).thenReturn(Mono.just(account("CTA-1001", "1000000")));

        StepVerifier.create(accountUseCase.withdraw("CTA-1001", new BigDecimal("250000")))
                .assertNext(account -> assertThat(account.getBalance()).isEqualByComparingTo("750000"))
                .verifyComplete();
    }

    @Test
    void rejectsAWithdrawalAboveTheBalanceWithoutSaving() {
        when(accountGateway.findByAccountNumber("CTA-1001")).thenReturn(Mono.just(account("CTA-1001", "100000")));

        StepVerifier.create(accountUseCase.withdraw("CTA-1001", new BigDecimal("600000")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(BusinessRuleException.class)
                        .extracting(e -> ((BusinessRuleException) e).getReason())
                        .isEqualTo(RejectionReason.INSUFFICIENT_BALANCE))
                .verify();

        verify(accountGateway, never()).save(any());
    }

    @Test
    void rejectsADepositToAnUnknownAccount() {
        when(accountGateway.findByAccountNumber("CTA-9999")).thenReturn(Mono.empty());

        StepVerifier.create(accountUseCase.deposit("CTA-9999", new BigDecimal("250000")))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(accountGateway, never()).save(any());
    }

    // ---------- Límite diario configurable ----------

    @Test
    void updatesTheDailyLimitLockingTheAccountFirst() {
        when(accountGateway.findByAccountNumber("CTA-1001")).thenReturn(Mono.just(account("CTA-1001", "10000000")));

        StepVerifier.create(accountUseCase.updateDailyLimit("CTA-1001", new BigDecimal("3000000")))
                .assertNext(account -> assertThat(account.getDailyLimit()).isEqualByComparingTo("3000000"))
                .verifyComplete();

        InOrder order = inOrder(accountGateway);
        order.verify(accountGateway).lock(List.of("CTA-1001"));
        order.verify(accountGateway).findByAccountNumber("CTA-1001");
        order.verify(accountGateway).save(any());
    }

    @Test
    void rejectsAnInvalidLimitWithoutSaving() {
        when(accountGateway.findByAccountNumber("CTA-1001")).thenReturn(Mono.just(account("CTA-1001", "10000000")));

        StepVerifier.create(accountUseCase.updateDailyLimit("CTA-1001", BigDecimal.ZERO))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(accountGateway, never()).save(any());
    }

    @Test
    void rejectsALimitForAnUnknownAccount() {
        when(accountGateway.findByAccountNumber("CTA-9999")).thenReturn(Mono.empty());

        StepVerifier.create(accountUseCase.updateDailyLimit("CTA-9999", new BigDecimal("3000000")))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(accountGateway, never()).save(any());
    }

    // ---------- Eliminación ----------

    @Test
    void deletesAnExistingAccount() {
        when(accountGateway.findById(1L)).thenReturn(Mono.just(account("CTA-1001", "0")));
        when(accountGateway.deleteById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(accountUseCase.deleteAccount(1L)).verifyComplete();

        verify(accountGateway).deleteById(1L);
    }

    @Test
    void rejectsDeletingAnUnknownAccount() {
        when(accountGateway.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(accountUseCase.deleteAccount(99L))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(accountGateway, never()).deleteById(any());
    }

    private static Account account(String accountNumber, String balance) {
        return new Account(1L, accountNumber, "Holder", new BigDecimal(balance), AccountType.SAVINGS, new BigDecimal("5000000"));
    }
}
