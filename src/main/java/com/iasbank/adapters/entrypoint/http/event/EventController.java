package com.iasbank.adapters.entrypoint.http.event;

import com.iasbank.adapters.entrypoint.http.PageResponse;
import com.iasbank.core.domain.constants.PaginationConstants;
import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.ports.input.EventUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Administration of the events published to RabbitMQ.
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventUseCase eventUseCase;

    @GetMapping
    public Mono<PageResponse<EventDto.Response>> getEvents(
            @RequestParam(required = false) EventStatus status,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) @Max(PaginationConstants.MAX_PAGE_SIZE) Integer size) {
        return eventUseCase.getEvents(
                        status,
                        page == null ? PaginationConstants.DEFAULT_PAGE : page,
                        size == null ? PaginationConstants.DEFAULT_PAGE_SIZE : size)
                .map(result -> PageResponse.fromDomain(result, EventDto.Response::fromDomain));
    }

    @GetMapping("/summary")
    public Mono<EventDto.Summary> getEventSummary() {
        return eventUseCase.getEventSummary().map(EventDto.Summary::fromDomain);
    }
}
