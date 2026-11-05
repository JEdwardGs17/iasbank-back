package com.iasbank.core.domain.model;

import com.iasbank.core.domain.errors.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Account - reglas de la cuenta")
class AccountTest {

    private static final BigDecimal DAILY_LIMIT = new BigDecimal("5000000");

    @Nested
    @DisplayName("RF02 - límite diario (5.000.000)")
    class DailyLimit {

        @ParameterizedTest(name = "consumido {0} + monto {1} → permitido")
        @CsvSource({
                "0, 600000",
                "4400000, 600000",
                "0, 5000000",
                "4999999, 1"
        })
        @DisplayName("se permite mientras no se supere el límite (alcanzarlo exactamente es válido)")
        void allowsUpToTheLimit(String authorizedToday, String amount) {
            Account account = account("10000000");

            assertThatCode(() -> account.validateDailyLimit(new BigDecimal(authorizedToday), new BigDecimal(amount)))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "consumido {0} + monto {1} → DAILY_LIMIT_EXCEEDED")
        @CsvSource({
                "5000000, 1",
                "4500000, 600000",
                "0, 5000001",
                "0, 6000000"
        })
        @DisplayName("se rechaza cuando la operación supera el límite")
        void rejectsAboveTheLimit(String authorizedToday, String amount) {
            Account account = account("10000000");

            assertRejectedWith(
                    () -> account.validateDailyLimit(new BigDecimal(authorizedToday), new BigDecimal(amount)),
                    RejectionReason.DAILY_LIMIT_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("Límite diario configurable")
    class ConfigurableDailyLimit {

        @Test
        @DisplayName("el límite se puede cambiar y se aplica a las siguientes validaciones")
        void changesTheLimit() {
            Account account = account("10000000");

            account.changeDailyLimit(new BigDecimal("1000000"));

            assertThat(account.getDailyLimit()).isEqualByComparingTo("1000000");
            assertThatCode(() -> account.validateDailyLimit(BigDecimal.ZERO, new BigDecimal("1000000"))).doesNotThrowAnyException();
            assertRejectedWith(() -> account.validateDailyLimit(BigDecimal.ZERO, new BigDecimal("1000001")),
                    RejectionReason.DAILY_LIMIT_EXCEEDED);
        }

        @ParameterizedTest(name = "límite {0} → inválido")
        @ValueSource(strings = {"0", "-1"})
        @DisplayName("el límite debe ser mayor que cero, y si es inválido no cambia")
        void rejectsNonPositiveLimit(String limit) {
            Account account = account("10000000");

            assertThatThrownBy(() -> account.changeDailyLimit(new BigDecimal(limit)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(account.getDailyLimit()).isEqualByComparingTo(DAILY_LIMIT);
        }
    }

    @Nested
    @DisplayName("Movimientos de saldo")
    class Balance {

        @Test
        @DisplayName("withdraw() debita y deposit() acredita el monto")
        void movesBalance() {
            Account account = account("1000000");

            account.withdraw(new BigDecimal("400000"));
            account.deposit(new BigDecimal("100000"));

            assertThat(account.getBalance()).isEqualByComparingTo("700000");
        }

        @Test
        @DisplayName("no se puede retirar más del saldo disponible, y el saldo no cambia")
        void rejectsInsufficientBalance() {
            Account account = account("100");

            assertRejectedWith(() -> account.withdraw(new BigDecimal("600000")), RejectionReason.INSUFFICIENT_BALANCE);
            assertThat(account.getBalance()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("los montos de depósito y retiro deben ser mayores que cero")
        void rejectsNonPositiveAmounts() {
            Account account = account("1000");

            assertRejectedWith(() -> account.withdraw(BigDecimal.ZERO), RejectionReason.INVALID_AMOUNT);
            assertRejectedWith(() -> account.deposit(new BigDecimal("-1")), RejectionReason.INVALID_AMOUNT);
        }
    }

    private static void assertRejectedWith(Executable operation, RejectionReason reason) {
        assertThatThrownBy(operation::execute)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(error -> ((BusinessRuleException) error).getReason())
                .isEqualTo(reason);
    }

    private static Account account(String balance) {
        return new Account(1L, "CTA-1001", "Holder", new BigDecimal(balance), AccountType.SAVINGS, DAILY_LIMIT);
    }
}
