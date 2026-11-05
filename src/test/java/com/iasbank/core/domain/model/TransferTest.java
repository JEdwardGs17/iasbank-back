package com.iasbank.core.domain.model;

import com.iasbank.core.domain.errors.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Transfer - reglas de la transferencia")
class TransferTest {

    @Nested
    @DisplayName("RF02 - validación")
    class Validation {

        @Test
        @DisplayName("una transferencia con monto positivo entre cuentas distintas es válida")
        void validTransfer() {
            assertThatCode(() -> transfer("CTA-1001", "CTA-2001", "600000").validate()).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "monto {0} → INVALID_AMOUNT")
        @ValueSource(strings = {"0", "-1", "-600000"})
        @DisplayName("el monto debe ser mayor que cero")
        void rejectsAmountNotGreaterThanZero(String amount) {
            assertRejectedWith(transfer("CTA-1001", "CTA-2001", amount), RejectionReason.INVALID_AMOUNT);
        }

        @Test
        @DisplayName("la cuenta origen y la cuenta destino deben ser diferentes")
        void rejectsSameAccount() {
            assertRejectedWith(transfer("CTA-1001", "CTA-1001", "600000"), RejectionReason.SAME_ACCOUNT);
        }
    }

    @Nested
    @DisplayName("RF03 - resultado de la operación")
    class Result {

        @Test
        @DisplayName("authorize() registra el estado y la fecha de procesamiento, sin razón")
        void authorize() {
            Transfer request = transfer("CTA-1001", "CTA-2001", "600000");

            Transfer authorized = request.authorize();

            assertThat(authorized.getStatus()).isEqualTo(TransferStatus.AUTHORIZED);
            assertThat(authorized.getProcessedAt()).isNotNull();
            assertThat(authorized.getRejectionReason()).isNull();
            assertThat(request.getStatus()).as("el modelo es inmutable").isNull();
        }

        @Test
        @DisplayName("reject() registra el estado, la razón y la fecha de procesamiento")
        void reject() {
            Transfer rejected = transfer("CTA-1001", "CTA-2001", "6000000").reject(RejectionReason.DAILY_LIMIT_EXCEEDED);

            assertThat(rejected.getStatus()).isEqualTo(TransferStatus.REJECTED);
            assertThat(rejected.getRejectionReason()).isEqualTo(RejectionReason.DAILY_LIMIT_EXCEEDED);
            assertThat(rejected.getProcessedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("RF05 - comparación de solicitudes repetidas")
    class SameRequest {

        private final Transfer original = transfer("CTA-1001", "CTA-2001", "600000.00");

        @Test
        @DisplayName("los mismos datos son la misma solicitud, aunque el monto tenga otra escala")
        void sameData() {
            assertThat(original.isSameRequestAs(transfer("CTA-1001", "CTA-2001", "600000"))).isTrue();
        }

        @Test
        @DisplayName("otro monto es una solicitud diferente")
        void differentAmount() {
            assertThat(original.isSameRequestAs(transfer("CTA-1001", "CTA-2001", "900000"))).isFalse();
        }

        @Test
        @DisplayName("otra cuenta origen o destino es una solicitud diferente")
        void differentAccounts() {
            assertThat(original.isSameRequestAs(transfer("CTA-1002", "CTA-2001", "600000"))).isFalse();
            assertThat(original.isSameRequestAs(transfer("CTA-1001", "CTA-1002", "600000"))).isFalse();
        }
    }

    private static void assertRejectedWith(Transfer transfer, RejectionReason reason) {
        assertThatThrownBy(transfer::validate)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(error -> ((BusinessRuleException) error).getReason())
                .isEqualTo(reason);
    }

    private static Transfer transfer(String source, String destination, String amount) {
        return Transfer.builder()
                .requestReference("REF-001")
                .sourceAccountId(source)
                .destinationAccountId(destination)
                .amount(new BigDecimal(amount))
                .build();
    }
}
