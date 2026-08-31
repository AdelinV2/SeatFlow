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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MdcLoggingFilterTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @BeforeEach
    @AfterEach
    void tearDown() {
        MDC.clear();
        CorrelationContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should generate a fresh UUID correlation ID when header is absent and populate dotted MDC fields")
    void shouldGenerateCorrelationIdWhenAbsentAndPopulateMdc() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reservations");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        HttpServletResponse response = mock(HttpServletResponse.class);

        Map<String, String> capturedMdc = new HashMap<>();
        FilterChain chain = (req, res) -> capturedMdc.putAll(MDC.getCopyOfContextMap());

        filter.doFilter(request, response, chain);

        // Captured inside the filter before finally cleanup
        assertThat(capturedMdc.get(StructuredLogFields.CORRELATION_ID)).isNotBlank();
        UUID generated = UUID.fromString(capturedMdc.get(StructuredLogFields.CORRELATION_ID));
        assertThat(generated).isNotNull();

        assertThat(capturedMdc.get(StructuredLogFields.HTTP_METHOD)).isEqualTo("POST");
        assertThat(capturedMdc.get(StructuredLogFields.HTTP_URI)).isEqualTo("/api/reservations");
        assertThat(capturedMdc.get(StructuredLogFields.HTTP_CLIENT_IP)).isEqualTo("10.0.0.5");

        // Response header set
        verify(response).setHeader(CORRELATION_HEADER, capturedMdc.get(StructuredLogFields.CORRELATION_ID));

        // MDC purged after completion
        assertThat(MDC.get(StructuredLogFields.CORRELATION_ID)).isNull();
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();
    }

    @Test
    @DisplayName("Should reject invalid non-UUID correlation ID header and generate a new UUID")
    void shouldGenerateNewCorrelationIdWhenIncomingHeaderIsInvalidUuid() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn("invalid-non-uuid-string-123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/events");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedCorrId = new String[1];
        FilterChain chain = (req, res) -> capturedCorrId[0] = MDC.get(StructuredLogFields.CORRELATION_ID);

        filter.doFilter(request, response, chain);

        assertThat(capturedCorrId[0]).isNotEqualTo("invalid-non-uuid-string-123");
        UUID validUuid = UUID.fromString(capturedCorrId[0]);
        assertThat(validUuid).isNotNull();
        verify(response).setHeader(CORRELATION_HEADER, capturedCorrId[0]);
    }

    @Test
    @DisplayName("Should reuse incoming valid UUID correlation ID")
    void shouldReuseIncomingValidUuidCorrelationId() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        String incomingUuid = UUID.randomUUID().toString();

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(incomingUuid);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/tickets");
        when(request.getRemoteAddr()).thenReturn("192.168.1.10");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedCorrId = new String[1];
        FilterChain chain = (req, res) -> capturedCorrId[0] = MDC.get(StructuredLogFields.CORRELATION_ID);

        filter.doFilter(request, response, chain);

        assertThat(capturedCorrId[0]).isEqualTo(incomingUuid);
        verify(response).setHeader(CORRELATION_HEADER, incomingUuid);
    }

    @Test
    @DisplayName("Should populate user.id when authenticated principal is present in SecurityContext")
    void shouldPopulateUserIdWhenAuthenticated() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();

        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user-uuid-12345",
                "credentials",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/profile");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedUserId = new String[1];
        FilterChain chain = (req, res) -> capturedUserId[0] = MDC.get(StructuredLogFields.USER_ID);

        filter.doFilter(request, response, chain);

        assertThat(capturedUserId[0]).isEqualTo("user-uuid-12345");
    }

    @Test
    @DisplayName("Should populate trace.id and span.id when Micrometer Tracer is active")
    void shouldPopulateTraceAndSpanIds() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);

        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(traceContext.spanId()).thenReturn("00f067aa0ba902b7");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("tracer", tracer);

        MdcLoggingFilter filter = new MdcLoggingFilter(beanFactory.getBeanProvider(Tracer.class));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/events");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        HttpServletResponse response = mock(HttpServletResponse.class);

        Map<String, String> capturedMdc = new HashMap<>();
        FilterChain chain = (req, res) -> capturedMdc.putAll(MDC.getCopyOfContextMap());

        filter.doFilter(request, response, chain);

        assertThat(capturedMdc.get(StructuredLogFields.TRACE_ID)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(capturedMdc.get(StructuredLogFields.SPAN_ID)).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    @DisplayName("Should preserve and restore outer MDC context in finally block")
    void shouldPreserveAndRestoreOuterMdcContext() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();

        // Simulate pre-existing outer MDC context (e.g. from async thread pool / upstream filter)
        MDC.put("outer.key", "outer.value");
        MDC.put("parent.trace", "pt-999");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedInnerKey = new String[1];
        String[] capturedOuterKey = new String[1];
        FilterChain chain = (req, res) -> {
            capturedInnerKey[0] = MDC.get(StructuredLogFields.HTTP_METHOD);
            capturedOuterKey[0] = MDC.get("outer.key");
        };

        filter.doFilter(request, response, chain);

        assertThat(capturedInnerKey[0]).isEqualTo("GET");
        assertThat(capturedOuterKey[0]).isEqualTo("outer.value");

        // After filter completes, outer context is completely restored
        assertThat(MDC.get("outer.key")).isEqualTo("outer.value");
        assertThat(MDC.get("parent.trace")).isEqualTo("pt-999");
        assertThat(MDC.get(StructuredLogFields.HTTP_METHOD)).isNull();
    }

    @Test
    @DisplayName("Should extract client IP from X-Forwarded-For when trust-forwarded-headers is enabled")
    void shouldExtractClientIpFromXForwardedForWhenEnabled() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        ReflectionTestUtils.setField(filter, "trustForwardedHeaders", true);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/x");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 70.41.3.18, 150.172.238.178");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedIp = new String[1];
        FilterChain chain = (req, res) -> capturedIp[0] = MDC.get(StructuredLogFields.HTTP_CLIENT_IP);
        filter.doFilter(request, response, chain);

        assertThat(capturedIp[0]).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("Should ignore X-Forwarded-For by default when trust-forwarded-headers is false")
    void shouldIgnoreXForwardedForByDefault() throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/x");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 70.41.3.18");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedIp = new String[1];
        FilterChain chain = (req, res) -> capturedIp[0] = MDC.get(StructuredLogFields.HTTP_CLIENT_IP);
        filter.doFilter(request, response, chain);

        assertThat(capturedIp[0]).isEqualTo("10.0.0.1");
    }
}
