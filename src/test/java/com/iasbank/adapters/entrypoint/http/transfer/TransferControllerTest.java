package com.iasbank.adapters.entrypoint.http.transfer;

import com.iasbank.adapters.entrypoint.http.GlobalExceptionHandler;
import com.iasbank.core.domain.dto.PageResult;
import com.iasbank.core.domain.dto.TransferResult;
import com.iasbank.core.domain.errors.RequestReferenceConflictException;
import com.iasbank.core.domain.model.RejectionReason;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.ports.input.TransferUseCase;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(TransferController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("API /api/transfers - contrato HTTP")
class TransferControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private TransferUseCase transferUseCase;

    @Nested
    @DisplayName("POST /api/transfers")
    class ProcessTransfer {

        @Test
        @DisplayName("RF01: una transferencia nueva responde 201 con el resultado")
        void newTransferReturnsCreated() {
            when(transferUseCase.processTransfer(any())).thenReturn(Mono.just(TransferResult.processed(authorized())));

            post(validRequest())
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.requestReference").isEqualTo("REF-001")
                    .jsonPath("$.status").isEqualTo("AUTHORIZED")
                    .jsonPath("$.rejectionReason").doesNotExist()
                    .jsonPath("$.processedAt").exists();
        }

        @Test
        @DisplayName("RF03: una transferencia rechazada responde 201 con su razón")
        void rejectedTransferIncludesReason() {
            Transfer rejected = request().reject(RejectionReason.DAILY_LIMIT_EXCEEDED);
            when(transferUseCase.processTransfer(any())).thenReturn(Mono.just(TransferResult.processed(rejected)));

            post(validRequest())
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("REJECTED")
                    .jsonPath("$.rejectionReason").isEqualTo("DAILY_LIMIT_EXCEEDED");
        }

        @Test
        @DisplayName("RF05: una referencia ya procesada con los mismos datos responde 200 con el resultado original")
        void replayReturnsOk() {
            when(transferUseCase.processTransfer(any())).thenReturn(Mono.just(TransferResult.replayed(authorized())));

            post(validRequest())
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.id").isEqualTo(1);
        }

        @Test
        @DisplayName("RF05: una referencia ya procesada con datos diferentes responde 422")
        void conflictReturnsUnprocessableEntity() {
            when(transferUseCase.processTransfer(any()))
                    .thenReturn(Mono.error(new RequestReferenceConflictException("REF-001")));

            post(validRequest())
                    .expectStatus().isEqualTo(422)
                    .expectBody().jsonPath("$.error").exists();
        }

        @Test
        @DisplayName("RF02: los campos requeridos faltantes responden 400 por campo y no se procesa nada")
        void missingFieldsReturnBadRequest() {
            post("{\"requestReference\":\"\",\"amount\":null}")
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.requestReference").exists()
                    .jsonPath("$.sourceAccountId").exists()
                    .jsonPath("$.destinationAccountId").exists()
                    .jsonPath("$.amount").exists();

            verifyNoInteractions(transferUseCase);
        }

        @Test
        @DisplayName("RF02: un monto no positivo no es un error de formato, se evalúa como regla de negocio")
        void nonPositiveAmountReachesTheBusinessRules() {
            Transfer rejected = request().toBuilder().amount(BigDecimal.ZERO).build().reject(RejectionReason.INVALID_AMOUNT);
            when(transferUseCase.processTransfer(any())).thenReturn(Mono.just(TransferResult.processed(rejected)));

            post("{\"requestReference\":\"REF-001\",\"sourceAccountId\":\"CTA-1001\",\"destinationAccountId\":\"CTA-2001\",\"amount\":0}")
                    .expectStatus().isCreated()
                    .expectBody().jsonPath("$.rejectionReason").isEqualTo("INVALID_AMOUNT");
        }
    }

    @Nested
    @DisplayName("GET /api/transfers/{requestReference}")
    class GetByReference {

        @Test
        @DisplayName("RF06: una referencia existente responde 200 con la transferencia")
        void found() {
            when(transferUseCase.getTransferByReference("REF-001")).thenReturn(Mono.just(authorized()));

            client.get().uri("/api/transfers/REF-001").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.requestReference").isEqualTo("REF-001");
        }

        @Test
        @DisplayName("RF06: una referencia inexistente responde 404")
        void notFound() {
            when(transferUseCase.getTransferByReference("NOPE")).thenReturn(Mono.empty());

            client.get().uri("/api/transfers/NOPE").exchange().expectStatus().isNotFound();
        }
    }

    @Nested
    @DisplayName("GET /api/transfers")
    class GetRecent {

        @Test
        @DisplayName("RF06: sin parámetros usa la página 0 de 20 y responde los metadatos de paginación")
        void defaultPage() {
            when(transferUseCase.getRecentTransfers(0, 20))
                    .thenReturn(Mono.just(new PageResult<>(List.of(authorized()), 0, 20, 21)));

            client.get().uri("/api/transfers").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.content.length()").isEqualTo(1)
                    .jsonPath("$.page").isEqualTo(0)
                    .jsonPath("$.size").isEqualTo(20)
                    .jsonPath("$.totalElements").isEqualTo(21)
                    .jsonPath("$.totalPages").isEqualTo(2);
        }

        @Test
        @DisplayName("RF06: tamaño de página fuera de 1..100 o página negativa responden 400")
        void invalidPagination() {
            client.get().uri("/api/transfers?size=0").exchange().expectStatus().isBadRequest();
            client.get().uri("/api/transfers?size=101").exchange().expectStatus().isBadRequest();
            client.get().uri("/api/transfers?page=-1").exchange().expectStatus().isBadRequest();

            verify(transferUseCase, never()).getRecentTransfers(anyInt(), anyInt());
        }
    }

    private WebTestClient.ResponseSpec post(String body) {
        return client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static String validRequest() {
        return "{\"requestReference\":\"REF-001\",\"sourceAccountId\":\"CTA-1001\",\"destinationAccountId\":\"CTA-2001\",\"amount\":600000}";
    }

    private static Transfer request() {
        return Transfer.builder()
                .id(1L)
                .requestReference("REF-001")
                .sourceAccountId("CTA-1001")
                .destinationAccountId("CTA-2001")
                .amount(new BigDecimal("600000"))
                .build();
    }

    private static Transfer authorized() {
        return request().authorize();
    }
}
