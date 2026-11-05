package com.iasbank.adapters.entrypoint.http.account;

import com.iasbank.core.domain.model.Account;
import com.iasbank.core.domain.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * HTTP messages of the accounts API.
 */
public final class AccountDto {

    private AccountDto() {
    }

    public record CreateRequest(
            @NotBlank(message = "Account number is required") String accountNumber,
            @NotBlank(message = "Holder is required") String holder,
            @NotNull(message = "Initial balance is required") BigDecimal balance,
            @NotNull(message = "Account type is required") AccountType accountType,
            @NotNull(message = "Daily limit is required") BigDecimal dailyLimit
    ) {

        public Account toDomain() {
            return new Account(null, accountNumber, holder, balance, accountType, dailyLimit);
        }
    }

    /** Deposit or withdrawal. */
    public record TransactionRequest(
            @NotBlank(message = "Account number is required") String accountNumber,
            @NotNull(message = "Amount is required") @Positive(message = "Amount must be positive") BigDecimal amount
    ) {
    }

    public record DailyLimitRequest(
            @NotNull(message = "Daily limit is required") @Positive(message = "Daily limit must be greater than zero") BigDecimal dailyLimit
    ) {
    }

    public record Response(
            Long id,
            String accountNumber,
            String holder,
            BigDecimal balance,
            AccountType accountType,
            BigDecimal dailyLimit
    ) {

        public static Response fromDomain(Account account) {
            return new Response(
                    account.getId(),
                    account.getAccountNumber(),
                    account.getHolder(),
                    account.getBalance(),
                    account.getAccountType(),
                    account.getDailyLimit()
            );
        }
    }
}
