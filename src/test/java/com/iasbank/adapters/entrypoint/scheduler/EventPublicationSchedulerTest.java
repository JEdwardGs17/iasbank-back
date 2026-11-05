package com.iasbank.adapters.entrypoint.scheduler;

import com.iasbank.core.ports.input.EventUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventPublicationScheduler - ejecución periódica de la publicación")
class EventPublicationSchedulerTest {

    @Mock
    private EventUseCase eventUseCase;

    @InjectMocks
    private EventPublicationScheduler scheduler;

    @Test
    @DisplayName("una ejecución fallida termina sin error, para que las siguientes ejecuciones continúen")
    void aFailedRunDoesNotStopTheScheduler() {
        when(eventUseCase.publishPendingEvents()).thenReturn(Mono.error(new IllegalStateException("database unavailable")));

        StepVerifier.create(scheduler.publishPendingEvents()).verifyComplete();
    }
}
