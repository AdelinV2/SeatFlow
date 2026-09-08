package com.seatflow.analytics.web;

import com.seatflow.analytics.config.SecurityConfig;
import com.seatflow.analytics.service.AdminAnalyticsQueryService;
import com.seatflow.analytics.service.AnalyticsCsvExportService;
import com.seatflow.analytics.web.controller.AdminAnalyticsController;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.analytics.web.dto.response.AnalyticsFilterOptionsResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsSummaryResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.converter.JwtRoleConverter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-P14-004 slice tests: ADMIN authorization boundary plus HTTP-level range/sort parsing.
 *
 * <p>Uses a fixed Clock (2026-09-06Z) so default-range behavior is deterministic.
 */
@WebMvcTest(AdminAnalyticsController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, AdminAnalyticsControllerTest.FixedClock.class})
class AdminAnalyticsControllerTest {

    static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock analyticsClock() {
            return Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAnalyticsQueryService queryService;

    @MockitoBean
    private AnalyticsCsvExportService csvExportService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    // ------------------------------------------------------------------
    // Authorization: every endpoint requires ROLE_ADMIN server-side
    // ------------------------------------------------------------------

    @Test
    void shouldReject401ForAnonymousOnAllEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/timeseries").param("metric", "GROSS_REVENUE"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/sessions/{id}", SESSION_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/top").param("metric", "NET_REVENUE"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/filter-options/events"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/filter-options/sessions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/export/daily.csv"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReject403ForCustomerAndStaff() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .with(user("customer").roles(SecurityRoles.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .with(user("staff").roles(SecurityRoles.STAFF)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/sessions/{id}", SESSION_ID)
                        .with(user("customer").roles(SecurityRoles.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/filter-options/events")
                        .with(user("staff").roles(SecurityRoles.STAFF)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/export/daily.csv")
                        .with(user("customer").roles(SecurityRoles.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldExecuteForAdminOnAllEndpoints() throws Exception {
        when(queryService.getSummary(any(), any(), any())).thenReturn(emptySummary());
        when(queryService.getSessions(any(), any(), any(int.class), any(int.class), any(), any(boolean.class), any()))
                .thenReturn(PagedResult.of(List.of(), 0, 25, 0));

        mockMvc.perform(get("/api/admin/analytics/summary")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/analytics/timeseries")
                        .param("metric", "TICKETS_ISSUED")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/analytics/sessions")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/analytics/top")
                        .param("metric", "TICKETS_ISSUED")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Range validation at the HTTP boundary (stable error codes)
    // ------------------------------------------------------------------

    @Test
    void shouldDefaultToLast30DaysViaFixedClock() throws Exception {
        when(queryService.getSummary(any(), any(), any())).thenReturn(emptySummary());

        mockMvc.perform(get("/api/admin/analytics/summary")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk());

        ArgumentCaptor<AnalyticsDateRange> captor = ArgumentCaptor.forClass(AnalyticsDateRange.class);
        verify(queryService).getSummary(captor.capture(), eq(null), eq(null));
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(captor.getValue().to()).isEqualTo(LocalDate.of(2026, 9, 6));
    }

    @Test
    void shouldReject400WhenExactlyOneBoundarySupplied() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2026-08-08")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_ANALYTICS_DATE_RANGE.getCode()));

        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("to", "2026-09-06")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_ANALYTICS_DATE_RANGE.getCode()));
    }

    @Test
    void shouldReject400WhenFromIsAfterTo() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2026-09-06")
                        .param("to", "2026-08-08")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_ANALYTICS_DATE_RANGE.getCode()));
    }

    @Test
    void shouldReject400ForMalformedDate() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "08/08/2026")
                        .param("to", "2026-09-06")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_ANALYTICS_DATE_RANGE.getCode()));
    }

    @Test
    void shouldAcceptExactly366DaysButReject367() throws Exception {
        when(queryService.getSummary(any(), any(), any())).thenReturn(emptySummary());

        // 2025-09-06..2026-09-06 inclusive = 366 days -> accepted, service executes.
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2025-09-06")
                        .param("to", "2026-09-06")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk());

        // 2025-09-05..2026-09-06 inclusive = 367 days -> rejected before any query.
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2025-09-05")
                        .param("to", "2026-09-06")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.ANALYTICS_DATE_RANGE_TOO_LARGE.getCode()));
    }

    // ------------------------------------------------------------------
    // Sort parsing + service error propagation with stable codes
    // ------------------------------------------------------------------

    @Test
    void shouldReject400ForMalformedUuidAndPagingParams() throws Exception {
        // REV-001: type-mismatch failures must be stable 400s with the common shape, never 500.
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("eventId", "not-a-uuid")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_REQUEST.getCode()))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/admin/analytics/summary"));

        mockMvc.perform(get("/api/admin/analytics/sessions/{id}", "not-a-uuid")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_REQUEST.getCode()));

        mockMvc.perform(get("/api/admin/analytics/sessions")
                        .param("size", "abc")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_REQUEST.getCode()));
    }

    @Test
    void shouldReject400ForUnknownSortField() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/sessions")
                        .param("sort", "revenue;DROP")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_ANALYTICS_SORT.getCode()));
    }

    @Test
    void shouldReject400ForUnknownSortDirection() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/sessions")
                        .param("sort", "startsAt,sideways")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_ANALYTICS_SORT.getCode()));
    }

    @Test
    void shouldPropagateStableServiceValidationCodes() throws Exception {
        when(queryService.getSessions(any(), any(), any(int.class), any(int.class), any(), any(boolean.class), any()))
                .thenThrow(new ValidationException("bad size", ErrorCode.INVALID_ANALYTICS_LIMIT));

        mockMvc.perform(get("/api/admin/analytics/sessions")
                        .param("size", "101")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_ANALYTICS_LIMIT.getCode()));
    }

    @Test
    void shouldMapUnknownSessionDetailTo404() throws Exception {
        when(queryService.getSessionDetail(SESSION_ID))
                .thenThrow(new ResourceNotFoundException("Analytics session", SESSION_ID));

        mockMvc.perform(get("/api/admin/analytics/sessions/{id}", SESSION_ID)
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.RESOURCE_NOT_FOUND.getCode()));
    }

    // ------------------------------------------------------------------
    // TASK-P14-006: filter options + CSV export boundary
    // ------------------------------------------------------------------

    @Test
    void shouldExecuteFilterAndExportEndpointsForAdmin() throws Exception {
        when(queryService.getEventFilterOptions(any()))
                .thenReturn(AnalyticsFilterOptionsResponse.empty());
        when(queryService.getSessionFilterOptions(any(), any()))
                .thenReturn(AnalyticsFilterOptionsResponse.empty());
        when(csvExportService.exportDailyCsv(any(), any(), any()))
                .thenReturn("row_type,metric_date\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/admin/analytics/filter-options/events")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.totalProjected").value(0));

        mockMvc.perform(get("/api/admin/analytics/filter-options/sessions")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/admin/analytics/export/daily.csv")
                        .param("from", "2026-09-05")
                        .param("to", "2026-09-06")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(
                        result.getResponse().getContentType()).startsWith("text/csv"))
                .andExpect(result -> assertThat(result.getResponse()
                        .getHeader("Content-Disposition"))
                        .contains("seatflow-analytics-2026-09-05-2026-09-06.csv"));
    }

    @Test
    void shouldValidateDateRangeOnFilterAndExportEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/filter-options/events")
                        .param("from", "2026-09-06")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_ANALYTICS_DATE_RANGE.getCode()));

        mockMvc.perform(get("/api/admin/analytics/export/daily.csv")
                        .param("from", "2025-09-05")
                        .param("to", "2026-09-06")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.ANALYTICS_DATE_RANGE_TOO_LARGE.getCode()));

        mockMvc.perform(get("/api/admin/analytics/filter-options/sessions")
                        .param("eventId", "not-a-uuid")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.INVALID_REQUEST.getCode()));
    }

    @Test
    void shouldPropagateExportTooLargeWithoutPartialBody() throws Exception {
        when(csvExportService.exportDailyCsv(any(), any(), any()))
                .thenThrow(new ValidationException(
                        "Analytics export matches 10001 rows", ErrorCode.ANALYTICS_EXPORT_TOO_LARGE));

        mockMvc.perform(get("/api/admin/analytics/export/daily.csv")
                        .param("from", "2026-09-05")
                        .param("to", "2026-09-06")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(ErrorCode.ANALYTICS_EXPORT_TOO_LARGE.getCode()))
                .andExpect(result -> assertThat(
                        result.getResponse().getContentAsString()).doesNotContain("row_type"));
    }

    private static AnalyticsSummaryResponse emptySummary() {
        return new AnalyticsSummaryResponse(
                LocalDate.of(2026, 8, 8), LocalDate.of(2026, 9, 6),
                new AnalyticsSummaryResponse.Filters(null, null),
                new AnalyticsSummaryResponse.Reservations(0, 0, 0, 0),
                new AnalyticsSummaryResponse.Tickets(0, 0, 0),
                new AnalyticsSummaryResponse.Payments(0, 0, 0, List.of()),
                new AnalyticsSummaryResponse.Rates(
                        com.seatflow.analytics.web.dto.response.RateMetricResponse.of(0, 0),
                        com.seatflow.analytics.web.dto.response.RateMetricResponse.of(0, 0),
                        com.seatflow.analytics.web.dto.response.RateMetricResponse.of(0, 0)),
                com.seatflow.analytics.web.dto.response.ProjectionFreshnessResponse.of(
                        Instant.parse("2026-09-06T12:00:00Z"), null, null));
    }
}
