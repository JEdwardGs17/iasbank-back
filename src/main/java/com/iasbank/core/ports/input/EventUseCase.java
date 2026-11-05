package com.iasbank.core.ports.input;

import com.iasbank.core.domain.dto.EventSummary;
import com.iasbank.core.domain.dto.PageResult;
import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.domain.model.OutboxEvent;
import reactor.core.publisher.Mono;

/**
 * Casos de uso de eventos (punto opcional - RabbitMQ).
 */
public interface EventUseCase {

    /**
     * Publica en el broker los eventos pendientes o en reintento cuyo próximo intento ya venció.
     * Cada falla programa un reintento con espera creciente; tras 3 reintentos el evento queda FAILED.
     */
    Mono<Void> publishPendingEvents();

    /** Eventos para la vista de administración, del más reciente al más antiguo; status null = todos. */
    Mono<PageResult<OutboxEvent>> getEvents(EventStatus status, int page, int size);

    /** Cantidad de eventos por estado y mensajes actualmente en la cola del broker. */
    Mono<EventSummary> getEventSummary();
}
