package com.seatflow.common.observability.filter;

import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.logging.StructuredLogFields;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MdcLoggingFilterTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @BeforeEach
    @AfterEach
    void clearContext() {
        MDC.clear();
        CorrelationContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldGenerateValidCorrelationIdAndPopulateDottedRequestFields() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        HttpServletRequest request = request("POST", "/api/reservations", "10.0.0.5");
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        Map<String, String> capturedMdc = new HashMap<>();
        FilterChain chain = (req, res) -> capturedMdc.putAll(MDC.getCopyOfContextMap());

        filter.doFilter(request, response, chain);

        String correlationId = capturedMdc.get(StructuredLogFields.CORRELATION_ID);
        assertThat(UUID.fromString(correlationId)).isNotNull();
        assertThat(capturedMdc)
                .containsEntry(StructuredLogFields.HTTP_METHOD, "POST")
                .containsEntry(StructuredLogFields.HTTP_URI, "/api/reservations")
                .containsEntry(StructuredLogFields.HTTP_CLIENT_IP, "10.0.0.5");
        verify(response).setHeader(CORRELATION_HEADER, correlationId);
        assertThat(MDC.get(StructuredLogFields.CORRELATION_ID)).isNull();
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();
    }

    @Test
    void shouldRejectInvalidCorrelationIdAndEchoGeneratedUuid() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        HttpServletRequest request = request("GET", "/api/events", "127.0.0.1");
        when(request.getHeader(CORRELATION_HEADER)).thenReturn("1-1-1-1-1");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedCorrelationId = new String[1];
        FilterChain chain = (req, res) -> capturedCorrelationId[0] = MDC.get(StructuredLogFields.CORRELATION_ID);

        filter.doFilter(request, response, chain);

        assertThat(capturedCorrelationId[0]).isNotEqualTo("1-1-1-1-1");
        assertThat(UUID.fromString(capturedCorrelationId[0])).isNotNull();
        verify(response).setHeader(CORRELATION_HEADER, capturedCorrelationId[0]);
    }

    @Test
    void shouldReuseValidCorrelationId() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        String incomingCorrelationId = UUID.randomUUID().toString();
        HttpServletRequest request = request("GET", "/api/tickets", "192.168.1.10");
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(incomingCorrelationId);
        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedCorrelationId = new String[1];
        FilterChain chain = (req, res) -> capturedCorrelationId[0] = MDC.get(StructuredLogFields.CORRELATION_ID);

        filter.doFilter(request, response, chain);

        assertThat(capturedCorrelationId[0]).isEqualTo(incomingCorrelationId);
        verify(response).setHeader(CORRELATION_HEADER, incomingCorrelationId);
    }

    @Test
    void shouldPopulateAuthenticatedUsernameAsUserId() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "user-uuid-12345",
                        "credentials",
                        Collections.emptyList()
                )
        );
        HttpServletRequest request = request("GET", "/api/profile", "127.0.0.1");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedUserId = new String[1];
        FilterChain chain = (req, res) -> capturedUserId[0] = MDC.get(StructuredLogFields.USER_ID);

        filter.doFilter(request, response, chain);

        assertThat(capturedUserId[0]).isEqualTo("user-uuid-12345");
    }

    @Test
    void shouldInjectTraceAndSpanFromAutowiredTracerProvider() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(traceContext.spanId()).thenReturn("00f067aa0ba902b7");

        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        when(tracerProvider.getIfAvailable()).thenReturn(tracer);
        MdcLoggingFilter filter = new MdcLoggingFilter(tracerProvider);

        HttpServletRequest request = request("GET", "/api/events", "127.0.0.1");
        HttpServletResponse response = mock(HttpServletResponse.class);
        Map<String, String> capturedMdc = new HashMap<>();
        FilterChain chain = (req, res) -> capturedMdc.putAll(MDC.getCopyOfContextMap());

        filter.doFilter(request, response, chain);

        assertThat(capturedMdc)
                .containsEntry(StructuredLogFields.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736")
                .containsEntry(StructuredLogFields.SPAN_ID, "00f067aa0ba902b7");
    }

    @Test
    void shouldRestorePreviousMdcContextAndClearCorrelationContext() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        MDC.put("outer.key", "outer.value");
        MDC.put(StructuredLogFields.HTTP_METHOD, "OUTER");
        HttpServletRequest request = request("GET", "/api/test", "127.0.0.1");
        HttpServletResponse response = mock(HttpServletResponse.class);

        Map<String, String> capturedMdc = new HashMap<>();
        FilterChain chain = (req, res) -> capturedMdc.putAll(MDC.getCopyOfContextMap());

        filter.doFilter(request, response, chain);

        assertThat(capturedMdc).containsEntry("outer.key", "outer.value");
        assertThat(capturedMdc).containsEntry(StructuredLogFields.HTTP_METHOD, "GET");
        assertThat(MDC.get("outer.key")).isEqualTo("outer.value");
        assertThat(MDC.get(StructuredLogFields.HTTP_METHOD)).isEqualTo("OUTER");
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();
    }

    @Test
    void shouldRestorePreviousMdcContextWhenFilterChainFails() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        MDC.put("outer.key", "outer.value");
        HttpServletRequest request = request("GET", "/api/failure", "127.0.0.1");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new jakarta.servlet.ServletException("boom");
        })).isInstanceOf(jakarta.servlet.ServletException.class);

        assertThat(MDC.get("outer.key")).isEqualTo("outer.value");
        assertThat(MDC.get(StructuredLogFields.HTTP_URI)).isNull();
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();
    }

    @Test
    void shouldUseRemoteAddressUnlessForwardedHeadersAreExplicitlyTrusted() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        HttpServletRequest request = request("GET", "/api/x", "10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 70.41.3.18");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedIp = new String[1];
        filter.doFilter(request, response,
                (req, res) -> capturedIp[0] = MDC.get(StructuredLogFields.HTTP_CLIENT_IP));

        assertThat(capturedIp[0]).isEqualTo("10.0.0.1");

        ReflectionTestUtils.setField(filter, "trustForwardedHeaders", true);
        filter.doFilter(request, response,
                (req, res) -> capturedIp[0] = MDC.get(StructuredLogFields.HTTP_CLIENT_IP));
        assertThat(capturedIp[0]).isEqualTo("203.0.113.7");
    }

    private HttpServletRequest request(String method, String uri, String remoteAddress) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getRemoteAddr()).thenReturn(remoteAddress);
        return request;
    }
}
