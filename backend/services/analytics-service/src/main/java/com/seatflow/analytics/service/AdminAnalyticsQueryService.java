package com.seatflow.analytics.service;

import com.seatflow.analytics.model.enums.AnalyticsSessionSort;
import com.seatflow.analytics.model.enums.AnalyticsTimeseriesMetric;
import com.seatflow.analytics.model.enums.AnalyticsTopMetric;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.analytics.web.dto.response.AnalyticsEventFilterOptionResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsFilterOptionsResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsSessionFilterOptionResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsSummaryResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsTimeSeriesResponse;
import com.seatflow.analytics.web.dto.response.EventSessionAnalyticsResponse;
import com.seatflow.analytics.web.dto.response.TopAnalyticsItemResponse;
import com.seatflow.common.domain.dto.PagedResult;

import java.util.List;
import java.util.UUID;

/**
 * Read-only admin analytics queries over the {@code seatflow_analytics} read model.
 *
 * <p>All results come solely from analytics-owned facts/aggregates. No method fans out to
 * Event, Reservation, Payment, Ticket, Seat Map, or Grafana services.
 */
public interface AdminAnalyticsQueryService {

    AnalyticsSummaryResponse getSummary(
            AnalyticsDateRange range, UUID eventId, UUID eventSessionId);

    AnalyticsTimeSeriesResponse getTimeSeries(
            AnalyticsDateRange range, UUID eventId, UUID eventSessionId,
            AnalyticsTimeseriesMetric metric);

    PagedResult<EventSessionAnalyticsResponse> getSessions(
            AnalyticsDateRange range, UUID eventId,
            int page, int size, AnalyticsSessionSort sort, boolean descending, String currency);

    EventSessionAnalyticsResponse getSessionDetail(UUID eventSessionId);

    List<TopAnalyticsItemResponse> getTop(
            AnalyticsDateRange range, UUID eventId,
            AnalyticsTopMetric metric, int limit, String currency);

    AnalyticsFilterOptionsResponse<AnalyticsEventFilterOptionResponse> getEventFilterOptions(
            AnalyticsDateRange range);

    AnalyticsFilterOptionsResponse<AnalyticsSessionFilterOptionResponse> getSessionFilterOptions(
            AnalyticsDateRange range, UUID eventId);
}
