package com.seatflow.notification.client.impl;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.notification.client.TicketServiceClient;
import com.seatflow.notification.client.exception.TicketClientUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Slf4j
@Component
public class TicketServiceClientImpl implements TicketServiceClient {

    private static final String CIRCUIT_BREAKER_NAME = "ticketService";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public TicketServiceClientImpl(
            @Qualifier("ticketServiceLoadBalancedBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${ticket-service.base-url:http://ticket-service}") String baseUrl) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.restClient = loadBalancedBuilder
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    CorrelationContext.getCorrelationId()
                            .ifPresent(id -> request.getHeaders().set("X-Correlation-Id", id));
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public byte[] fetchTicketPdf(UUID ticketId) {
        log.debug("Calling ticket-service to fetch PDF for ticketId={}", ticketId);

        try {
            return circuitBreaker.executeSupplier(() -> doFetchTicketPdf(ticketId));
        } catch (CallNotPermittedException e) {
            log.error("Circuit breaker is OPEN for ticket-service when fetching ticketId={}", ticketId, e);
            throw new TicketClientUnavailableException("Ticket service is temporarily unavailable (circuit breaker open)", e);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch PDF from ticket-service for ticketId={}", ticketId, e);
            throw new TicketClientUnavailableException("Failed to fetch ticket PDF: " + e.getMessage(), e);
        }
    }

    private byte[] doFetchTicketPdf(UUID ticketId) {
        byte[] pdfBytes = restClient.get()
                .uri("/api/tickets/{ticketId}/pdf", ticketId)
                .accept(MediaType.APPLICATION_PDF)
                .retrieve()
                .onStatus(status -> status.value() == 404, (req, res) -> {
                    throw new ResourceNotFoundException("Ticket PDF not found for ticketId: " + ticketId);
                })
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new TicketClientUnavailableException("Ticket service error [" + res.getStatusCode() + "]");
                })
                .body(byte[].class);

        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new TicketClientUnavailableException("Empty PDF response received from ticket-service for ticketId: " + ticketId);
        }

        log.info("Successfully retrieved ticket PDF from ticket-service: ticketId={}, sizeBytes={}",
                ticketId, pdfBytes.length);
        return pdfBytes;
    }
}
