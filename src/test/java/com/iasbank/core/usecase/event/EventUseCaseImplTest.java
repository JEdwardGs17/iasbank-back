package com.iasbank.core.usecase.event;

import com.iasbank.core.domain.model.EventStatus;
import com.iasbank.core.domain.model.OutboxEvent;
import com.iasbank.core.domain.model.Transfer;
import com.iasbank.core.ports.output.EventGateway;
import com.iasbank.core.ports.output.EventPublisherGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventUseCaseImplTest {

    @Mock
    private EventGateway eventGateway;

    @Mock
    private EventPublisherGateway eventPublisherGateway;

    @InjectMocks
    private EventUseCaseImpl eventUseCase;

    @Test
    void marksTheEventAsPublishedWhenTheBrokerConfirmsIt() {
        givenDueEvents(pendingEvent());
        when(eventPublisherGateway.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(eventUseCase.publishPendingEvents()).verifyComplete();

        assertThat(savedEvent().getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void schedulesARetryWhenThePublicationFails() {
        givenDueEvents(pendingEvent());
        when(eventPublisherGateway.publish(any())).thenReturn(Mono.error(new IllegalStateException("Connection refused")));

        StepVerifier.create(eventUseCase.publishPendingEvents()).verifyComplete();

        OutboxEvent saved = savedEvent();
        assertThat(saved.getStatus()).isEqualTo(EventStatus.RETRYING);
        assertThat(saved.getLastError()).isEqualTo("Connection refused");
        assertThat(saved.getNextAttemptAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void marksTheEventAsFailedWhenTheThirdRetryFails() {
        OutboxEvent onLastRetry = pendingEvent()
                .registerFailedAttempt("down", LocalDateTime.now())
                .registerFailedAttempt("down", LocalDateTime.now())
                .registerFailedAttempt("down", LocalDateTime.now());
        givenDueEvents(onLastRetry);
        when(eventPublisherGateway.publish(any())).thenReturn(Mono.error(new IllegalStateException("down")));

        StepVerifier.create(eventUseCase.publishPendingEvents()).verifyComplete();

        assertThat(savedEvent().getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(savedEvent().retries()).isEqualTo(3);
    }

    @Test
    void takesTheCurrentTimeOnEverySubscriptionOfTheSameMono() {
        when(eventGateway.findDueForPublication(any(LocalDateTime.class), anyInt())).thenReturn(Flux.empty());
        Mono<Void> scheduledRun = eventUseCase.publishPendingEvents();

        scheduledRun.block();
        scheduledRun.block();

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(eventGateway, times(2)).findDueForPublication(now.capture(), anyInt());
        assertThat(now.getAllValues().get(1)).isAfterOrEqualTo(now.getAllValues().get(0));
    }

    @Test
    void summaryCountsEventsByStatusAndTheMessagesInTheQueue() {
        when(eventGateway.count(EventStatus.PENDING)).thenReturn(Mono.just(1L));
        when(eventGateway.count(EventStatus.RETRYING)).thenReturn(Mono.just(2L));
        when(eventGateway.count(EventStatus.PUBLISHED)).thenReturn(Mono.just(5L));
        when(eventGateway.count(EventStatus.FAILED)).thenReturn(Mono.just(1L));
        when(eventPublisherGateway.queuedMessages()).thenReturn(Mono.just(5L));

        StepVerifier.create(eventUseCase.getEventSummary())
                .assertNext(summary -> {
                    assertThat(summary.pending()).isEqualTo(1);
                    assertThat(summary.retrying()).isEqualTo(2);
                    assertThat(summary.published()).isEqualTo(5);
                    assertThat(summary.failed()).isEqualTo(1);
                    assertThat(summary.queuedMessages()).isEqualTo(5L);
                })
                .verifyComplete();
    }

    @Test
    void summaryStillWorksWhenTheBrokerIsNotReachable() {
        when(eventGateway.count(any())).thenReturn(Mono.just(0L));
        when(eventPublisherGateway.queuedMessages()).thenReturn(Mono.empty());

        StepVerifier.create(eventUseCase.getEventSummary())
                .assertNext(summary -> assertThat(summary.queuedMessages()).isNull())
                .verifyComplete();
    }

    @Test
    void returnsTheRequestedPageOfEvents() {
        when(eventGateway.findNewestFirst(EventStatus.FAILED, 0, 10)).thenReturn(Flux.just(pendingEvent()));
        when(eventGateway.count(EventStatus.FAILED)).thenReturn(Mono.just(11L));

        StepVerifier.create(eventUseCase.getEvents(EventStatus.FAILED, 0, 10))
                .assertNext(page -> {
                    assertThat(page.content()).hasSize(1);
                    assertThat(page.totalPages()).isEqualTo(2);
                })
                .verifyComplete();
    }

    private void givenDueEvents(OutboxEvent event) {
        when(eventGateway.findDueForPublication(any(LocalDateTime.class), anyInt())).thenReturn(Flux.just(event));
        when(eventGateway.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private OutboxEvent savedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(eventGateway).save(captor.capture());
        return captor.getValue();
    }

    private static OutboxEvent pendingEvent() {
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
