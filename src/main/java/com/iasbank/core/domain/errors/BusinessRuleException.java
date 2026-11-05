package com.iasbank.core.domain.errors;

import com.iasbank.core.domain.model.RejectionReason;
import lombok.Getter;

/**
 * Raised when an operation violates a business rule of the domain.
 */
@Getter
public class BusinessRuleException extends RuntimeException {

    private final RejectionReason reason;

    public BusinessRuleException(RejectionReason reason) {
        super(reason.getDescription());
        this.reason = reason;
    }
}
