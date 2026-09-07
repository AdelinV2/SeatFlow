package com.seatflow.ai.client.impl;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.dto.EventDetailClientDto;
import com.seatflow.ai.client.dto.EventSessionClientDto;
import com.seatflow.ai.client.dto.EventSummaryClientDto;
import com.seatflow.ai.client.exception.EventServiceNotFoundException;
import com.seatflow.ai.client.exception.EventServiceUnavailableException;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.common.domain.dto.PagedResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Eureka + LoadBalancer Event Service client for AI read-only tools.
 *
 * <p>Conventions mirror the sibling inter-service clients (constitution invariant 10):
 * logical service-name base URL, explicit connect/read timeouts, correlation + Bearer propagation,
 * and a Resilience4j circuit breaker. HTTP failures are mapped into the stable AI tool error
 * taxonomy without leaking raw bodies or tokens into messages or logs.
 */
@Slf4j
@Component
public class EventServiceClientImpl implements EventServiceClient {

    static final String CIRCUIT_BREAKER_NAME = "eventService";
    static final String EVENT_SERVICE_BASE_URL = "http://event-service";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final ParameterizedTypeReference<PagedResult<EventSummaryClientDto>> SEARCH_PAGE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<EventSessionClientDto>> SESSION_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient.Builder loadBalancedBuilder;
    private final CircuitBreaker circuitBreaker;
    private final String baseUrl;
    private volatile RestClient restClient;

    @Autowired
    public EventServiceClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedBuilder,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${event-service.base-url:http://event-service}") String baseUrl) {
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.baseUrl = normalizeDiscoveryUrl(baseUrl);
        this.restClient = null;
    }

    /** Test-only path: use a prebuilt client (e.g. bound to a mock HTTP server). */
    EventServiceClientImpl(RestClient restClient, CircuitBreaker circuitBreaker, String baseUrl) {
        this.loadBalancedBuilder = null;
        this.circuitBreaker = circuitBreaker;
        this.baseUrl = baseUrl;
        this.restClient = restClient;
    }

    @Override
    public PagedResult<EventSummaryClientDto> searchEvents(
            String query, String category, int page, int size, AiRequestContext context) {
        requireContext(context);
        try {
            return circuitBreaker.executeSupplier(() -> fetchSearchPage(query, category, page, size, context));
        } catch (CallNotPermittedException e) {
            log.warn("event-service circuit open for searchEvents: queryPresent={}, category={}",
                    query != null, category);
            throw new EventServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                    "Event search is temporarily unavailable. Please try again shortly.", e);
        }
    }

    @Override
    public EventDetailClientDto getEvent(UUID eventId, AiRequestContext context) {
        requireContext(context);
        try {
            return circuitBreaker.executeSupplier(() -> fetchEvent(eventId, context));
        } catch (CallNotPermittedException e) {
            log.warn("event-service circuit open for getEvent: eventId={}", eventId);
            throw new EventServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                    "Event details are temporarily unavailable. Please try again shortly.", e);
        }
    }

    @Override
    public List<EventSessionClientDto> listSessions(UUID eventId, AiRequestContext context) {
        requireContext(context);
        try {
            return circuitBreaker.executeSupplier(() -> fetchSessions(eventId, context));
        } catch (CallNotPermittedException e) {
            log.warn("event-service circuit open for listSessions: eventId={}", eventId);
            throw new EventServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                    "Event sessions are temporarily unavailable. Please try again shortly.", e);
        }
    }

    private PagedResult<EventSummaryClientDto> fetchSearchPage(
            String query, String category, int page, int size, AiRequestContext context) {
        log.debug("Fetching event catalog page from event-service: page={}, size={}, queryPresent={}, category={}",
                page, size, query != null, category);
        try {
            PagedResult<EventSummaryClientDto> response = client().get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/api/events")
                                .queryParam("page", page)
                                .queryParam("size", size)
                                .queryParam("sort", "nextSessionStartsAt,asc");
                        if (query != null) {
                            uriBuilder.queryParam("search", query);
                        }
                        if (category != null) {
                            uriBuilder.queryParam("category", category);
                        }
                        return uriBuilder.build();
                    })
                    .headers(headers -> applyPropagation(headers, context))
                    .retrieve()
                    .onStatus(status -> status.value() == 400, (req, res) -> {
                        throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                                "Event search was rejected by the event catalog.");
                    })
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new AiToolException(AiToolError.UNAUTHENTICATED,
                                "Authentication is required to search events.");
                    })
                    .onStatus(status -> status.value() == 403, (req, res) -> {
                        throw new AiToolException(AiToolError.FORBIDDEN,
                                "You are not allowed to search events with these credentials.");
                    })
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new EventServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                                "Event search is unexpectedly unavailable.");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new EventServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                                "Event search is temporarily unavailable. Please try again shortly.");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new EventServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                                "Event search failed unexpectedly.");
                    })
                    .body(SEARCH_PAGE_TYPE);
            if (response == null || response.content() == null) {
                throw new EventServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                        "Event search returned an empty response.");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw mapTransportFailure("Event search", e);
        }
    }

    private EventDetailClientDto fetchEvent(UUID eventId, AiRequestContext context) {
        log.debug("Fetching event detail from event-service: eventId={}", eventId);
        try {
            EventDetailClientDto response = client().get()
                    .uri("/api/events/{eventId}", eventId)
                    .headers(headers -> applyPropagation(headers, context))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new EventServiceNotFoundException("Event", eventId);
                    })
                    .onStatus(status -> status.value() == 400, (req, res) -> {
                        throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                                "The event lookup was rejected by the event catalog.");
                    })
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new AiToolException(AiToolError.UNAUTHENTICATED,
                                "Authentication is required to read event details.");
                    })
                    .onStatus(status -> status.value() == 403, (req, res) -> {
                        throw new AiToolException(AiToolError.FORBIDDEN,
                                "You are not allowed to read this event with these credentials.");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new EventServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                                "Event details are temporarily unavailable. Please try again shortly.");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new EventServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                                "Event lookup failed unexpectedly.");
                    })
                    .body(EventDetailClientDto.class);
            if (response == null) {
                throw new EventServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                        "Event lookup returned an empty response.");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw mapTransportFailure("Event lookup", e);
        }
    }

    private List<EventSessionClientDto> fetchSessions(UUID eventId, AiRequestContext context) {
        log.debug("Fetching event sessions from event-service: eventId={}", eventId);
        try {
            List<EventSessionClientDto> response = client().get()
                    .uri("/api/events/{eventId}/sessions", eventId)
                    .headers(headers -> applyPropagation(headers, context))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new EventServiceNotFoundException("Event", eventId);
                    })
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new AiToolException(AiToolError.UNAUTHENTICATED,
                                "Authentication is required to read event sessions.");
                    })
                    .onStatus(status -> status.value() == 403, (req, res) -> {
                        throw new AiToolException(AiToolError.FORBIDDEN,
                                "You are not allowed to read these sessions with these credentials.");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new EventServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                                "Event sessions are temporarily unavailable. Please try again shortly.");
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new EventServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                                "Event session lookup failed unexpectedly.");
                    })
                    .body(SESSION_LIST_TYPE);
            if (response == null) {
                throw new EventServiceUnavailableException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                        "Event session lookup returned an empty response.");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw mapTransportFailure("Event session lookup", e);
        }
    }

    private EventServiceUnavailableException mapTransportFailure(String operation, ResourceAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                log.warn("{} timed out against event-service", operation);
                return new EventServiceUnavailableException(AiToolError.DOWNSTREAM_TIMEOUT,
                        operation + " timed out. Please try again shortly.", e);
            }
            cause = cause.getCause();
        }
        log.warn("{} failed against event-service: {}", operation, e.getMessage());
        return new EventServiceUnavailableException(AiToolError.DOWNSTREAM_UNAVAILABLE,
                operation + " is temporarily unavailable. Please try again shortly.", e);
    }

    private void requireContext(AiRequestContext context) {
        if (context == null || !context.isAuthenticated()) {
            throw new AiToolException(AiToolError.UNAUTHENTICATED,
                    "Authentication is required to use the AI assistant tools.");
        }
    }

    private void applyPropagation(HttpHeaders headers, AiRequestContext context) {
        headers.set("X-Correlation-Id", context.correlationId());
        headers.setBearerAuth(context.bearerToken());
    }

    private RestClient client() {
        RestClient client = this.restClient;
        if (client == null) {
            synchronized (this) {
                client = this.restClient;
                if (client == null) {
                    this.restClient = client = buildClient();
                }
            }
        }
        return client;
    }

    private RestClient buildClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return loadBalancedBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private String normalizeDiscoveryUrl(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            return EVENT_SERVICE_BASE_URL;
        }
        try {
            URI uri = URI.create(configuredBaseUrl.trim());
            String host = uri.getHost();
            if (host != null && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1"))) {
                log.warn("Ignoring direct event-service URL {} for the Eureka load-balanced client; using {} instead",
                        configuredBaseUrl, EVENT_SERVICE_BASE_URL);
                return EVENT_SERVICE_BASE_URL;
            }
            return configuredBaseUrl.trim().replaceAll("/+$", "");
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid event-service URL {}; using Eureka service ID {} instead",
                    configuredBaseUrl, EVENT_SERVICE_BASE_URL);
            return EVENT_SERVICE_BASE_URL;
        }
    }
}
