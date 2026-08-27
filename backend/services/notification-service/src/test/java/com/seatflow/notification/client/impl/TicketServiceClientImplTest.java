package com.seatflow.notification.client.impl;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.notification.client.TicketServiceClient;
import com.seatflow.notification.client.exception.TicketClientUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TicketServiceClientImplTest {

    private MockRestServiceServer mockServer;
    private TicketServiceClient ticketServiceClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

        ticketServiceClient = new TicketServiceClientImpl(
                builder,
                circuitBreakerRegistry,
                "http://ticket-service"
        );
    }

    @Test
    @DisplayName("Should successfully fetch PDF bytes from ticket-service")
    void shouldFetchPdfBytesSuccessfully() {
        UUID ticketId = UUID.randomUUID();
        byte[] expectedPdf = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF

        mockServer.expect(requestTo("http://ticket-service/api/tickets/" + ticketId + "/pdf"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(expectedPdf, MediaType.APPLICATION_PDF));

        byte[] actualPdf = ticketServiceClient.fetchTicketPdf(ticketId);

        mockServer.verify();
        assertThat(actualPdf).isEqualTo(expectedPdf);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when ticket PDF is not found (404)")
    void shouldThrowResourceNotFoundOn404() {
        UUID ticketId = UUID.randomUUID();

        mockServer.expect(requestTo("http://ticket-service/api/tickets/" + ticketId + "/pdf"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> ticketServiceClient.fetchTicketPdf(ticketId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket PDF not found");

        mockServer.verify();
    }

    @Test
    @DisplayName("Should throw TicketClientUnavailableException on 500 server error")
    void shouldThrowUnavailableOn500() {
        UUID ticketId = UUID.randomUUID();

        mockServer.expect(requestTo("http://ticket-service/api/tickets/" + ticketId + "/pdf"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> ticketServiceClient.fetchTicketPdf(ticketId))
                .isInstanceOf(TicketClientUnavailableException.class);

        mockServer.verify();
    }
}
