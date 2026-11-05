package com.iasbank.adapters.repository.account;

import com.iasbank.core.domain.model.Account;
import com.iasbank.core.domain.model.AccountType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;

@Table("accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AccountEntity {

    @Id
    private Long id;

    @Column("account_number")
    private String accountNumber;

    private String holder;

    private BigDecimal balance;

    @Column("account_type")
    private AccountType accountType;

    @Column("daily_limit")
    private BigDecimal dailyLimit;

    public static AccountEntity fromDomain(Account account) {
        return new AccountEntity(
                account.getId(),
                account.getAccountNumber(),
                account.getHolder(),
                account.getBalance(),
                account.getAccountType(),
                account.getDailyLimit()
        );
    }

    public Account toDomain() {
        return new Account(id, accountNumber, holder, balance, accountType, dailyLimit);
    }
}
