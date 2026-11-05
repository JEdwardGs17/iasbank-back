package com.iasbank.core.domain.model;

import com.iasbank.core.domain.errors.BusinessRuleException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    private Long id;
    private String accountNumber;
    private String holder;
    private BigDecimal balance;
    private AccountType accountType;
    private BigDecimal dailyLimit;

    public void deposit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException(RejectionReason.INVALID_AMOUNT);
        }
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException(RejectionReason.INVALID_AMOUNT);
        }
        if (amount.compareTo(this.balance) > 0) {
            throw new BusinessRuleException(RejectionReason.INSUFFICIENT_BALANCE);
        }
        this.balance = this.balance.subtract(amount);
    }

    /**
     * The daily limit is configurable per account. It applies to the following transfers:
     * if it is lowered below what was already transferred today, new transfers are rejected.
     */
    public void changeDailyLimit(BigDecimal newDailyLimit) {
        if (newDailyLimit == null || newDailyLimit.signum() <= 0) {
            throw new IllegalArgumentException("Daily limit must be greater than zero");
        }
        this.dailyLimit = newDailyLimit;
    }

    public void validateDailyLimit(BigDecimal authorizedToday, BigDecimal amount) {
        if (authorizedToday.add(amount).compareTo(dailyLimit) > 0) {
            throw new BusinessRuleException(RejectionReason.DAILY_LIMIT_EXCEEDED);
        }
    }
}
