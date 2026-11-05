package com.iasbank.adapters.entrypoint.http.event;

import com.iasbank.adapters.entrypoint.http.GlobalExceptionHandler;
import com.iasbank.core.domain.dto.EventSummary;
import com.iasbank.core.domain.dto.PageResult;
import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.ports.input.EventUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;

@WebFluxTest(EventController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("API /api/events - administración de eventos")
class EventControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private EventUseCase eventUseCase;

    @Test
    @DisplayName("lista los eventos filtrados por estado, con reintentos y máximo de reintentos")
    void listsEventsByStatus() {
        OutboxEvent retrying = event().registerFailedAttempt("Connection refused", LocalDateTime.now())
                .registerFailedAttempt("Connection refused", LocalDateTime.now());
        when(eventUseCase.getEvents(EventStatus.RETRYING, 0, 20))
                .thenReturn(Mono.just(new PageResult<>(List.of(retrying), 0, 20, 1)));

        client.get().uri("/api/events?status=RETRYING").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].status").isEqualTo("RETRYING")
                .jsonPath("$.content[0].retries").isEqualTo(1)
                .jsonPath("$.content[0].maxRetries").isEqualTo(3)
                .jsonPath("$.content[0].lastError").isEqualTo("Connection refused")
                .jsonPath("$.content[0].payload.requestReference").isEqualTo("REF-001")
                .jsonPath("$.totalElements").isEqualTo(1);
    }

    @Test
    @DisplayName("un estado inválido responde 400")
    void invalidStatus() {
        client.get().uri("/api/events?status=UNKNOWN").exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("el resumen informa los eventos por estado y los mensajes en la cola")
    void summary() {
        when(eventUseCase.getEventSummary()).thenReturn(Mono.just(new EventSummary(1, 2, 5, 1, 5L)));

        client.get().uri("/api/events/summary").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.pending").isEqualTo(1)
                .jsonPath("$.retrying").isEqualTo(2)
                .jsonPath("$.published").isEqualTo(5)
                .jsonPath("$.failed").isEqualTo(1)
                .jsonPath("$.queuedMessages").isEqualTo(5)
                .jsonPath("$.maxRetries").isEqualTo(3);
    }

    private static OutboxEvent event() {
        return OutboxEvent.transferAuthorized(Transfer.builder()
                .id(1L)
                .requestReference("REF-001")
                .sourceAccountId("CTA-1001")
                .destinationAccountId("CTA-2001")
                .amount(new BigDecimal("600000"))
                .build()
                .authorize());
    }
}
