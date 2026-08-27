package com.seatflow.realtime.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class StompTestClientHelper {

    private final WebSocketStompClient stompClient;

    @SuppressWarnings("deprecation")
    public StompTestClientHelper() {
        this.stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        converter.setObjectMapper(mapper);
        this.stompClient.setMessageConverter(converter);
    }

    public StompSession connect(String url, StompHeaders connectHeaders) throws ExecutionException, InterruptedException, TimeoutException {
        WebSocketHttpHeaders wsHeaders = new WebSocketHttpHeaders();
        return stompClient.connectAsync(url, wsHeaders, connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                log.error("STOMP Test Client Exception: command={}, headers={}: {}", command, headers, exception.getMessage(), exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                log.error("STOMP Transport Error: {}", exception.getMessage(), exception);
            }
        }).get(5, TimeUnit.SECONDS);
    }

    public StompSession.Subscription subscribe(StompSession session, String topic, BlockingQueue<SeatStatusUpdateMessage> queue) {
        return session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return SeatStatusUpdateMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                log.info("STOMP Test Client received frame on topic {}: payload={}", topic, payload);
                if (payload instanceof SeatStatusUpdateMessage updateMessage) {
                    queue.offer(updateMessage);
                }
            }
        });
    }

    public void stop() {
        if (stompClient != null && stompClient.isRunning()) {
            stompClient.stop();
        }
    }
}
