package com.seatflow.realtime.messaging.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.realtime.dto.RedisSeatStatusEnvelope;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class RedisSeatStatusSubscriberTest {

    @Test
    void broadcastsEachValidRedisEnvelopeExactlyOnce() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SeatStatusBroadcaster broadcaster = mock(SeatStatusBroadcaster.class);
        RedisSeatStatusSubscriber subscriber = new RedisSeatStatusSubscriber(
                objectMapper, broadcaster, new SimpleMeterRegistry());
        SeatStatusUpdateMessage payload = SeatStatusUpdateMessage.of(
                UUID.randomUUID(), List.of(UUID.randomUUID()), SeatStatus.HELD, Instant.now().plusSeconds(900));
        RedisSeatStatusEnvelope envelope = new RedisSeatStatusEnvelope(
                "event-envelope-1", UUID.randomUUID(), "instance-a", Instant.now(), payload);
        byte[] body = objectMapper.writeValueAsBytes(envelope);

        subscriber.onMessage(new DefaultMessage("seatflow:realtime:seat-status".getBytes(), body), null);

        verify(broadcaster).broadcastSeatStatus(payload);
    }

    @Test
    void discardsMalformedPayloadWithoutBroadcasting() {
        SeatStatusBroadcaster broadcaster = mock(SeatStatusBroadcaster.class);
        RedisSeatStatusSubscriber subscriber = new RedisSeatStatusSubscriber(
                new ObjectMapper().findAndRegisterModules(), broadcaster, new SimpleMeterRegistry());

        subscriber.onMessage(new DefaultMessage("seatflow:realtime:seat-status".getBytes(), "not-json".getBytes()), null);

        org.mockito.Mockito.verifyNoInteractions(broadcaster);
    }

    @Test
    void suppressesKafkaReplayWithSameSourceEventId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SeatStatusBroadcaster broadcaster = mock(SeatStatusBroadcaster.class);
        RedisSeatStatusSubscriber subscriber = new RedisSeatStatusSubscriber(
                objectMapper, broadcaster, new SimpleMeterRegistry());
        SeatStatusUpdateMessage payload = SeatStatusUpdateMessage.of(
                UUID.randomUUID(), List.of(UUID.randomUUID()), SeatStatus.SOLD, null);
        RedisSeatStatusEnvelope first = new RedisSeatStatusEnvelope(
                "source-event-1", UUID.randomUUID(), "instance-a", Instant.now(), payload);
        RedisSeatStatusEnvelope replay = new RedisSeatStatusEnvelope(
                "source-event-1", UUID.randomUUID(), "instance-a", Instant.now(), payload);

        subscriber.onMessage(new DefaultMessage("channel".getBytes(), objectMapper.writeValueAsBytes(first)), null);
        subscriber.onMessage(new DefaultMessage("channel".getBytes(), objectMapper.writeValueAsBytes(replay)), null);

        verify(broadcaster, times(1)).broadcastSeatStatus(payload);
    }

    @Test
    void rejectsEnvelopeWithoutStableSourceIdentity() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SeatStatusBroadcaster broadcaster = mock(SeatStatusBroadcaster.class);
        RedisSeatStatusSubscriber subscriber = new RedisSeatStatusSubscriber(
                objectMapper, broadcaster, new SimpleMeterRegistry());
        SeatStatusUpdateMessage payload = SeatStatusUpdateMessage.of(
                UUID.randomUUID(), List.of(UUID.randomUUID()), SeatStatus.HELD, Instant.now());
        RedisSeatStatusEnvelope invalid = new RedisSeatStatusEnvelope(
                " ", UUID.randomUUID(), "instance-a", Instant.now(), payload);

        subscriber.onMessage(new DefaultMessage("channel".getBytes(), objectMapper.writeValueAsBytes(invalid)), null);

        org.mockito.Mockito.verifyNoInteractions(broadcaster);
    }
}
