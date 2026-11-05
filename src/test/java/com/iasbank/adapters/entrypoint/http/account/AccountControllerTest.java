package com.iasbank.adapters.entrypoint.http.account;

import com.iasbank.adapters.entrypoint.http.GlobalExceptionHandler;
import com.iasbank.core.domain.errors.BusinessRuleException;
import com.iasbank.core.domain.model.Account;
import com.iasbank.core.domain.model.AccountType;
import com.iasbank.core.domain.model.RejectionReason;
import com.iasbank.core.ports.input.AccountUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(AccountController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("API /api/accounts - contrato HTTP")
class AccountControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private AccountUseCase accountUseCase;

    @Nested
    @DisplayName("POST /api/accounts")
    class Create {

        @Test
        @DisplayName("una cuenta nueva responde 201 con la cuenta creada")
        void createsTheAccount() {
            when(accountUseCase.createAccount(any())).thenReturn(Mono.just(account("10000000", "5000000")));

            post("/api/accounts", """
                    {"accountNumber":"CTA-1001","holder":"Holder","balance":10000000,"accountType":"SAVINGS","dailyLimit":5000000}""")
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.accountNumber").isEqualTo("CTA-1001")
                    .jsonPath("$.dailyLimit").isEqualTo(5000000);
        }

        @Test
        @DisplayName("un número de cuenta existente responde 400 con el motivo")
        void duplicatedAccount() {
            when(accountUseCase.createAccount(any()))
                    .thenReturn(Mono.error(new IllegalArgumentException("Account already exists with number: CTA-1001")));

            post("/api/accounts", """
                    {"accountNumber":"CTA-1001","holder":"Holder","balance":0,"accountType":"SAVINGS","dailyLimit":5000000}""")
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error").isEqualTo("Account already exists with number: CTA-1001");
        }

        @Test
        @DisplayName("los campos requeridos faltantes responden 400 por campo y no se crea nada")
        void missingFields() {
            post("/api/accounts", "{}")
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.accountNumber").exists()
                    .jsonPath("$.holder").exists()
                    .jsonPath("$.balance").exists()
                    .jsonPath("$.accountType").exists()
                    .jsonPath("$.dailyLimit").exists();

            verifyNoInteractions(accountUseCase);
        }
    }

    @Nested
    @DisplayName("POST /api/accounts/deposit y /withdraw")
    class Movements {

        @Test
        @DisplayName("un depósito responde 200 con el nuevo saldo")
        void deposit() {
            when(accountUseCase.deposit(eq("CTA-1001"), any())).thenReturn(Mono.just(account("10250000", "5000000")));

            post("/api/accounts/deposit", "{\"accountNumber\":\"CTA-1001\",\"amount\":250000}")
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.balance").isEqualTo(10250000);
        }

        @Test
        @DisplayName("un retiro mayor al saldo responde 400 con el motivo")
        void insufficientBalance() {
            when(accountUseCase.withdraw(eq("CTA-1001"), any()))
                    .thenReturn(Mono.error(new BusinessRuleException(RejectionReason.INSUFFICIENT_BALANCE)));

            post("/api/accounts/withdraw", "{\"accountNumber\":\"CTA-1001\",\"amount\":99000000}")
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error").exists();
        }

        @Test
        @DisplayName("un monto faltante, cero o negativo responde 400 sin mover saldos")
        void invalidAmount() {
            post("/api/accounts/deposit", "{\"accountNumber\":\"CTA-1001\"}").expectStatus().isBadRequest();
            post("/api/accounts/deposit", "{\"accountNumber\":\"CTA-1001\",\"amount\":0}").expectStatus().isBadRequest();
            post("/api/accounts/withdraw", "{\"accountNumber\":\"CTA-1001\",\"amount\":-5}").expectStatus().isBadRequest();

            verifyNoInteractions(accountUseCase);
        }
    }

    @Nested
    @DisplayName("PUT /api/accounts/{número}/daily-limit")
    class DailyLimit {

        @Test
        @DisplayName("actualiza el límite y responde la cuenta con el nuevo límite")
        void updatesTheDailyLimit() {
            when(accountUseCase.updateDailyLimit(eq("CTA-1001"), any())).thenReturn(Mono.just(account("10000000", "3000000")));

            put("{\"dailyLimit\":3000000}")
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.accountNumber").isEqualTo("CTA-1001")
                    .jsonPath("$.dailyLimit").isEqualTo(3000000);
        }

        @Test
        @DisplayName("un límite faltante, cero o negativo responde 400 sin modificar nada")
        void invalidLimit() {
            put("{}").expectStatus().isBadRequest().expectBody().jsonPath("$.dailyLimit").exists();
            put("{\"dailyLimit\":0}").expectStatus().isBadRequest();
            put("{\"dailyLimit\":-5}").expectStatus().isBadRequest();

            verifyNoInteractions(accountUseCase);
        }
    }

    @Nested
    @DisplayName("GET y DELETE")
    class QueryAndDelete {

        @Test
        @DisplayName("una cuenta inexistente responde 404")
        void notFound() {
            when(accountUseCase.getAccountByNumber("CTA-9999")).thenReturn(Mono.empty());

            client.get().uri("/api/accounts/number/CTA-9999").exchange().expectStatus().isNotFound();
        }

        @Test
        @DisplayName("eliminar una cuenta responde 204")
        void deletes() {
            when(accountUseCase.deleteAccount(1L)).thenReturn(Mono.empty());

            client.delete().uri("/api/accounts/1").exchange().expectStatus().isNoContent();
        }
    }

    private WebTestClient.ResponseSpec post(String uri, String body) {
        return client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec put(String body) {
        return client.put().uri("/api/accounts/CTA-1001/daily-limit")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static Account account(String balance, String dailyLimit) {
        return new Account(1L, "CTA-1001", "Holder", new BigDecimal(balance), AccountType.SAVINGS, new BigDecimal(dailyLimit));
    }
}
