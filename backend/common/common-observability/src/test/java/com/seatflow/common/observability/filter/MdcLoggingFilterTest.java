package com.seatflow.common.observability.filter;

import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.logging.StructuredLogFields;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MdcLoggingFilterTest {

    private static final String CORRELATION_HEADER = MdcLoggingFilter.CORRELATION_ID_HEADER;

    @BeforeEach
    @AfterEach
    void clearThreadLocalState() {
        MDC.clear();
        CorrelationContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldGenerateCorrelationAndPopulateRequestContext() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        HttpServletRequest request = request("POST", "/api/reservations", "10.0.0.5", null, null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        Map<String, String> captured = new HashMap<>();

        filter.doFilter(request, response, captureMdc(captured));

        String correlationId = captured.get(StructuredLogFields.CORRELATION_ID);
        assertThat(UUID.fromString(correlationId)).isNotNull();
        assertThat(captured).containsEntry(StructuredLogFields.HTTP_METHOD, "POST")
                .containsEntry(StructuredLogFields.HTTP_URI, "/api/reservations")
                .containsEntry(StructuredLogFields.HTTP_CLIENT_IP, "10.0.0.5");
        verify(response).setHeader(CORRELATION_HEADER, correlationId);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();
    }

    @Test
    void shouldRejectMalformedCorrelationIdAndReuseCanonicalUuid() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        Map<String, String> malformedCaptured = new HashMap<>();

        filter.doFilter(request("GET", "/api/events", "127.0.0.1", "1-1-1-1-1", null), response,
                captureMdc(malformedCaptured));

        assertThat(malformedCaptured.get(StructuredLogFields.CORRELATION_ID)).isNotEqualTo("1-1-1-1-1");
        assertThat(UUID.fromString(malformedCaptured.get(StructuredLogFields.CORRELATION_ID))).isNotNull();

        String incoming = UUID.randomUUID().toString();
        Map<String, String> validCaptured = new HashMap<>();
        filter.doFilter(request("GET", "/api/events", "127.0.0.1", incoming, null), response,
                captureMdc(validCaptured));
        assertThat(validCaptured).containsEntry(StructuredLogFields.CORRELATION_ID, incoming);
    }

    @Test
    void shouldRestoreOuterMdcContextAfterSuccessAndFailure() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        MDC.put("outer.key", "outer-value");
        CorrelationContext.setCorrelationId("outer-correlation");

        filter.doFilter(request("GET", "/api/tickets", "127.0.0.1", null, null), mock(HttpServletResponse.class),
                captureMdc(new HashMap<>()));

        assertThat(MDC.getCopyOfContextMap()).containsEntry("outer.key", "outer-value");
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();

        assertThatThrownBy(() -> filter.doFilter(
                request("GET", "/api/tickets", "127.0.0.1", null, null),
                mock(HttpServletResponse.class),
                (ignoredRequest, ignoredResponse) -> { throw new ServletException("expected"); }
        )).isInstanceOf(ServletException.class);

        assertThat(MDC.getCopyOfContextMap()).containsEntry("outer.key", "outer-value");
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();
    }

    @Test
    void shouldAddAuthenticatedUserAndTrustedForwardedClientIp() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        ReflectionTestUtils.setField(filter, "trustForwardedHeaders", true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user-123", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Map<String, String> captured = new HashMap<>();

        filter.doFilter(request("GET", "/api/profile", "127.0.0.1", null, "203.0.113.10, 10.0.0.1"),
                mock(HttpServletResponse.class), captureMdc(captured));

        assertThat(captured).containsEntry(StructuredLogFields.USER_ID, "user-123")
                .containsEntry(StructuredLogFields.HTTP_CLIENT_IP, "203.0.113.10");
    }

    @Test
    void shouldAddOnlyValidActiveTraceAndSpanIds() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        when(tracerProvider.getIfAvailable()).thenReturn(tracer);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(traceContext.spanId()).thenReturn("00f067aa0ba902b7");
        Map<String, String> captured = new HashMap<>();

        new MdcLoggingFilter(tracerProvider).doFilter(
                request("GET", "/api/events", "127.0.0.1", null, null),
                mock(HttpServletResponse.class), captureMdc(captured));

        assertThat(captured).containsEntry(StructuredLogFields.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736")
                .containsEntry(StructuredLogFields.SPAN_ID, "00f067aa0ba902b7");

        when(traceContext.traceId()).thenReturn("00000000000000000000000000000000");
        when(traceContext.spanId()).thenReturn("invalid");
        captured.clear();
        new MdcLoggingFilter(tracerProvider).doFilter(
                request("GET", "/api/events", "127.0.0.1", null, null),
                mock(HttpServletResponse.class), captureMdc(captured));
        assertThat(captured).doesNotContainKeys(StructuredLogFields.TRACE_ID, StructuredLogFields.SPAN_ID);
    }

    @Test
    void shouldFilterAsyncDispatchesSoTheirContextIsScopedAndCleared() {
        assertThat(new MdcLoggingFilter().shouldNotFilterAsyncDispatch()).isFalse();
    }

    private FilterChain captureMdc(Map<String, String> captured) {
        return (ignoredRequest, ignoredResponse) -> captured.putAll(MDC.getCopyOfContextMap());
    }

    private HttpServletRequest request(String method, String uri, String remoteAddress, String correlationId,
                                       String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getRemoteAddr()).thenReturn(remoteAddress);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(correlationId);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }
}
