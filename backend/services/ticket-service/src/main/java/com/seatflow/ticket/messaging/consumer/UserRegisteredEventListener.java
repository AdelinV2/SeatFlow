package com.seatflow.ticket.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.ticket.messaging.event.UserRegisteredEvent;
import com.seatflow.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserRegisteredEventListener {

    private final TicketService ticketService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = EventTopics.USER_EVENTS,
        groupId = "ticket-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onUserRegistered(EventEnvelope<Object> envelope) {
        if (!"UserRegistered".equals(envelope.eventType())) {
            log.debug("Ignoring irrelevant user event type: {}", envelope.eventType());
            return;
        }

        UserRegisteredEvent payload = objectMapper.convertValue(envelope.payload(), UserRegisteredEvent.class);

        log.info("Processing UserRegistered event for guest ticket linking. userId={}, email={}",
                payload.userId(), payload.email());

        int claimedCount = ticketService.claimGuestTickets(payload.userId(), payload.email());

        log.info("Guest ticket linking complete. userId={}, claimedCount={}", payload.userId(), claimedCount);
    }
}
