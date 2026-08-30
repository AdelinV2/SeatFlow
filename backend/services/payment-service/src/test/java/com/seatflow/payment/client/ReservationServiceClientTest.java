package com.seatflow.payment.client.impl;

import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.client.exception.ReservationClientUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationServiceClientTest {

    private final UUID reservationId = UUID.randomUUID();
    private final String baseUrl = "http://reservation-service";

    private RestClient restClient;
    private RestClient.Builder builder;
    private RestClient.RequestHeadersUriSpec requestSpec;
    private RestClient.RequestHeadersSpec headersSpec;
    private RestClient.ResponseSpec responseSpec;
    private ReservationServiceClientImpl client;

    private final ArgumentCaptor<RestClient.ResponseSpec.ErrorHandler> errorHandlerCaptor =
            ArgumentCaptor.forClass(RestClient.ResponseSpec.ErrorHandler.class);
    private final ArgumentCaptor<String> baseUrlCaptor = ArgumentCaptor.forClass(String.class);
    private final ArgumentCaptor<ClientHttpRequestInterceptor> requestInterceptorCaptor =
            ArgumentCaptor.forClass(ClientHttpRequestInterceptor.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        builder = mock(RestClient.Builder.class);
        restClient = mock(RestClient.class);
        requestSpec = mock(RestClient.RequestHeadersUriSpec.class);
        headersSpec = mock(RestClient.RequestHeadersSpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.requestFactory(any())).thenReturn(builder);
        when(builder.requestInterceptor(any())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);

        when(restClient.get()).thenReturn(requestSpec);
        when(requestSpec.uri(anyString())).thenReturn(headersSpec);
        when(requestSpec.uri(anyString(), (Object) any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), errorHandlerCaptor.capture())).thenReturn(responseSpec);
        when(responseSpec.body(ReservationClientResponse.class)).thenReturn(buildResponse("PENDING", Instant.now().plusSeconds(900)));

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        client = new ReservationServiceClientImpl(builder, registry, baseUrl);
    }

    private ReservationClientResponse buildResponse(String status, Instant expiresAt) {
        return new ReservationClientResponse(
                reservationId, UUID.randomUUID(), UUID.randomUUID(), "cust@example.com",
                status, expiresAt, new BigDecimal("100.00"), 2, List.of(), Instant.now());
    }

    @Test
    void getReservationReturnsDetailsOnSuccess() {
        ReservationClientResponse response = client.getReservation(reservationId);

        assertThat(response.id()).isEqualTo(reservationId);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void localhostConfigurationStillUsesTheEurekaReservationServiceId() {
        client = new ReservationServiceClientImpl(builder, CircuitBreakerRegistry.ofDefaults(), "http://localhost:8084");
        client.getReservation(reservationId);

        verifyBuilderBaseUrl();
        assertThat(baseUrlCaptor.getValue()).isEqualTo("http://reservation-service");
    }

    @Test
    void forwardsAuthenticatedCallerJwtToReservationService() throws Exception {
        Jwt jwt = Jwt.withTokenValue("caller-jwt-token")
                .header("alg", "none")
                .subject("caller-user")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        client.getReservation(reservationId);

        verifyBuilderBaseUrl();
        verify(builder).requestInterceptor(requestInterceptorCaptor.capture());

        HttpHeaders headers = new HttpHeaders();
        ClientHttpRequest request = mock(ClientHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);

        requestInterceptorCaptor.getValue().intercept(request, new byte[0], execution);

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer caller-jwt-token");
    }

    @Test
    void getReservationMapsNotFoundToResourceNotFoundException() {
        assertErrorHandler(0, ResourceNotFoundException.class);
    }

    @Test
    void getReservationMapsServerErrorToUnavailableException() {
        assertErrorHandler(1, ReservationClientUnavailableException.class);
    }

    @Test
    void getReservationFallsBackWhenCircuitIsOpen() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        ReservationServiceClientImpl openClient = new ReservationServiceClientImpl(
                mock(RestClient.Builder.class), registry, baseUrl);
        registry.circuitBreaker("reservationService").transitionToOpenState();

        assertThatThrownBy(() -> openClient.getReservation(reservationId))
                .isInstanceOf(ReservationClientUnavailableException.class);
    }

    private <T extends Throwable> void assertErrorHandler(int handlerIndex, Class<T> expectedType) {
        // Prime the captors by exercising the real onStatus registration path.
        client.getReservation(reservationId);

        ClientHttpResponse res = mock(ClientHttpResponse.class);
        ClientHttpRequest req = mock(ClientHttpRequest.class);

        assertThatThrownBy(() -> {
            try {
                errorHandlerCaptor.getAllValues().get(handlerIndex).handle(req, res);
            } catch (java.io.IOException e) {
                throw new AssertionError("Unexpected IOException from error handler", e);
            }
        }).isInstanceOf(expectedType);
    }

    private void verifyBuilderBaseUrl() {
        org.mockito.Mockito.verify(builder).baseUrl(baseUrlCaptor.capture());
    }
}
