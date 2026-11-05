package com.iasbank.core.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RejectionReason {

    INVALID_AMOUNT("Amount must be greater than zero"),
    SAME_ACCOUNT("Source and destination accounts must be different"),
    ACCOUNT_NOT_FOUND("Account not found"),
    DAILY_LIMIT_EXCEEDED("Daily limit exceeded"),
    INSUFFICIENT_BALANCE("Insufficient balance");

    private final String description;
}
