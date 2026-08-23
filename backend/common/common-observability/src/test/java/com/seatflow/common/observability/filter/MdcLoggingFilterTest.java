package com.seatflow.common.observability.filter;

import com.seatflow.common.observability.context.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MdcLoggingFilterTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @AfterEach
    void tearDown() {
        MDC.clear();
        CorrelationContext.clear();
    }

    private MdcLoggingFilter newFilterWithServiceName(String serviceName) throws Exception {
        MdcLoggingFilter filter = new MdcLoggingFilter();
        Field field = MdcLoggingFilter.class.getDeclaredField("serviceName");
        field.setAccessible(true);
        field.set(filter, serviceName);
        return filter;
    }

    @Test
    void shouldGenerateCorrelationIdWhenAbsentAndPopulateMdc() throws Exception {
        MdcLoggingFilter filter = newFilterWithServiceName("reservation-service");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reservations");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] captured = new String[1];
        FilterChain chain = (req, res) -> captured[0] = MDC.get(MdcLoggingFilter.MDC_CORRELATION_ID);
        filter.doFilter(request, response, chain);

        // Captured inside the filter (before finally clear)
        assertThat(captured[0]).isNotBlank();

        // Response header set
        verify(response).setHeader(CORRELATION_HEADER, captured[0]);

        // MDC purged after completion
        assertThat(MDC.get(MdcLoggingFilter.MDC_CORRELATION_ID)).isNull();
        assertThat(MDC.get(MdcLoggingFilter.MDC_SERVICE_NAME)).isNull();
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();
    }

    @Test
    void shouldReuseIncomingCorrelationIdAndSetServiceName() throws Exception {
        MdcLoggingFilter filter = newFilterWithServiceName("ticket-service");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn("incoming-corr-123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/tickets");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.1.10");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedService = new String[1];
        String[] capturedUri = new String[1];
        String[] capturedIp = new String[1];
        FilterChain chain = (req, res) -> {
            capturedService[0] = MDC.get(MdcLoggingFilter.MDC_SERVICE_NAME);
            capturedUri[0] = MDC.get(MdcLoggingFilter.MDC_HTTP_URI);
            capturedIp[0] = MDC.get(MdcLoggingFilter.MDC_CLIENT_IP);
        };
        filter.doFilter(request, response, chain);

        verify(response).setHeader(CORRELATION_HEADER, "incoming-corr-123");
        assertThat(capturedService[0]).isEqualTo("ticket-service");
        assertThat(capturedUri[0]).isEqualTo("/api/tickets");
        assertThat(capturedIp[0]).isEqualTo("192.168.1.10");
    }

    @Test
    void shouldExtractClientIpFromXForwardedFor() throws Exception {
        MdcLoggingFilter filter = newFilterWithServiceName("svc");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/x");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 70.41.3.18, 150.172.238.178");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] capturedIp = new String[1];
        FilterChain chain = (req, res) -> capturedIp[0] = MDC.get(MdcLoggingFilter.MDC_CLIENT_IP);
        filter.doFilter(request, response, chain);

        assertThat(capturedIp[0]).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldIgnoreNullHeaderLookupsGracefully() throws Exception {
        // Ensures no NPE when header lookups return null in addition to correlation header
        MdcLoggingFilter filter = newFilterWithServiceName("svc");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(anyString())).thenReturn(null);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/y");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = (req, res) -> { };
        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq(CORRELATION_HEADER), anyString());
    }
}
