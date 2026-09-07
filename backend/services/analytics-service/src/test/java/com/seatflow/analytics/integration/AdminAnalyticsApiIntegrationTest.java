package com.seatflow.analytics.integration;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.entity.EventSessionMetric;
import com.seatflow.analytics.model.entity.EventSessionRevenueMetric;
import com.seatflow.analytics.model.entity.ProcessedEvent;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.DailyRevenueMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.ClassUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-P14-007 section 7.13 acceptance suite: full-stack admin analytics API security and
 * semantics over real PostgreSQL with the real Spring Security filter chain.
 *
 * <p>Uses the production {@code SecurityConfig} unchanged (no weakened test security, no
 * auto-commit): ADMIN/USER identities arrive as JWT authorities through the canonical
 * {@code JwtDecoder} test seam, exactly as the existing slice tests do. The Kafka listener
 * is not started (no broker needed for read-model queries); projection state is seeded
 * through the analytics-owned repositories.
 *
 * <p>Proves: USER forbidden, ADMIN success on summary/timeseries/sessions/top, 30-day default
 * under a fixed Clock, invalid/range-too-large rejection, zero-denominator null ratio,
 * same-session multi-currency preservation without count multiplication, financial sort
 * requiring currency, Test Mode flags, absence of PII fields, freshness envelope, and no
 * HTTP source-service test double (WireMock absent from the classpath and unused).
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminAnalyticsApiIntegrationTest {

    static final UUID EVENT_E3 = UUID.fromString("e3e3e3e3-3333-4333-8333-333333333333");
    static final UUID S4 = UUID.fromString("44444444-4444-4444-8444-444444444444");
    static final LocalDate SEP_06 = LocalDate.of(2026, 9, 6);
    static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_007_api_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // Shadow the self-referential application-test.yaml bootstrap placeholder; the Kafka
        // listener never starts in this test (spring.kafka.listener.auto-startup=false).
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:19092");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnalyticsSessionFactRepository sessionFacts;

    @Autowired
    private EventSessionMetricRepository sessionMetrics;

    @Autowired
    private EventSessionRevenueMetricRepository sessionRevenue;

    @Autowired
    private DailyOperationalMetricRepository dailyOperational;

    @Autowired
    private DailyRevenueMetricRepository dailyRevenue;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void seedS4() {
        dailyRevenue.deleteAll();
        dailyOperational.deleteAll();
        sessionRevenue.deleteAll();
        sessionMetrics.deleteAll();
        sessionFacts.deleteAll();
        processedEvents.deleteAll();

        sessionFacts.save(AnalyticsSessionFact.builder()
                .eventSessionId(S4).eventId(EVENT_E3)
                .eventTitle("Gala").sessionLabel("Evening")
                .startsAt(Instant.parse("2026-09-06T19:00:00Z")).status("SCHEDULED")
                .lastSourceEventAt(Instant.parse("2026-09-06T10:00:00Z")).updatedAt(NOW).build());
        sessionMetrics.save(EventSessionMetric.builder()
                .eventSessionId(S4).eventId(EVENT_E3)
                .reservationsCreated(2).reservationsConfirmed(2)
                .paymentsSucceeded(2).ticketsIssued(2)
                .lastProjectedEventAt(Instant.parse("2026-09-06T11:30:00Z")).updatedAt(NOW).build());
        sessionRevenue.save(EventSessionRevenueMetric.builder()
                .eventSessionId(S4).eventId(EVENT_E3).currency("RON")
                .paymentsSucceeded(1).grossRevenueMinor(10_000L).updatedAt(NOW).build());
        sessionRevenue.save(EventSessionRevenueMetric.builder()
                .eventSessionId(S4).eventId(EVENT_E3).currency("EUR")
                .paymentsSucceeded(1).grossRevenueMinor(2_000L).updatedAt(NOW).build());
        dailyOperational.save(DailyOperationalMetric.builder()
                .metricDate(SEP_06).eventId(EVENT_E3).eventSessionId(S4)
                .reservationsCreated(2).reservationsConfirmed(2)
                .paymentsSucceeded(2).ticketsIssued(2).updatedAt(NOW).build());
        dailyRevenue.save(DailyRevenueMetric.builder()
                .metricDate(SEP_06).eventId(EVENT_E3).eventSessionId(S4).currency("RON")
                .paymentsSucceeded(1).grossRevenueMinor(10_000L).updatedAt(NOW).build());
        dailyRevenue.save(DailyRevenueMetric.builder()
                .metricDate(SEP_06).eventId(EVENT_E3).eventSessionId(S4).currency("EUR")
                .paymentsSucceeded(1).grossRevenueMinor(2_000L).updatedAt(NOW).build());
        processedEvents.save(ProcessedEvent.builder()
                .eventId("p14-007-api-1").eventType("PaymentCompleted")
                .sourceTopic("seatflow.payment.events")
                .occurredAt(Instant.parse("2026-09-06T11:59:42Z"))
                .processedAt(Instant.parse("2026-09-06T11:59:43Z")).build());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    @DisplayName("7.13 anonymous callers are unauthorized on every analytics endpoint")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/timeseries").param("metric", "GROSS_REVENUE"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/sessions/{id}", S4))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/top").param("metric", "TICKETS_ISSUED"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/analytics/export/daily.csv"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("7.13 non-admin JWT authorities are forbidden on every analytics endpoint")
    void customerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/timeseries")
                        .param("metric", "GROSS_REVENUE").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/sessions").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/sessions/{id}", S4).with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/top")
                        .param("metric", "TICKETS_ISSUED").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/filter-options/events").with(customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/analytics/export/daily.csv").with(customer()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("7.13 ADMIN summary preserves S4 multi-currency grain with neutral counts")
    void adminSummaryPreservesMultiCurrencyGrain() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2026-09-06").param("to", "2026-09-06")
                        .param("eventId", EVENT_E3.toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.created").value(2))
                .andExpect(jsonPath("$.reservations.confirmed").value(2))
                .andExpect(jsonPath("$.tickets.issued").value(2))
                .andExpect(jsonPath("$.payments.succeeded").value(2))
                .andExpect(jsonPath("$.payments.revenueByCurrency.length()").value(2))
                .andExpect(jsonPath("$.payments.revenueByCurrency[*].currency",
                        hasItem("RON")))
                .andExpect(jsonPath("$.payments.revenueByCurrency[*].currency",
                        hasItem("EUR")))
                .andExpect(jsonPath("$.payments.revenueByCurrency[*].grossMinor",
                        hasItem(10_000)))
                .andExpect(jsonPath("$.payments.revenueByCurrency[*].grossMinor",
                        hasItem(2_000)))
                .andExpect(jsonPath("$.payments.revenueByCurrency[*].testMode",
                        hasItem(true)))
                .andExpect(jsonPath("$.freshness.eventuallyConsistent").value(true))
                .andExpect(jsonPath("$.freshness.lastProjectedEventAt")
                        .value("2026-09-06T10:00:00Z"))
                .andExpect(jsonPath("$.freshness.lastProcessedAt")
                        .value("2026-09-06T11:59:43Z"))
                .andExpect(content().string(not(containsString("customerEmail"))))
                .andExpect(content().string(not(containsString("customerName"))))
                .andExpect(content().string(not(containsString("card"))));
    }

    @Test
    @DisplayName("7.13 ADMIN timeseries/sessions/top succeed with currency-separated money")
    void adminTimeseriesSessionsTopSucceed() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/timeseries")
                        .param("from", "2026-09-06").param("to", "2026-09-06")
                        .param("eventId", EVENT_E3.toString())
                        .param("metric", "GROSS_REVENUE").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(2));

        mockMvc.perform(get("/api/admin/analytics/timeseries")
                        .param("from", "2026-09-06").param("to", "2026-09-06")
                        .param("eventId", EVENT_E3.toString())
                        .param("metric", "TICKETS_ISSUED").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(1));

        mockMvc.perform(get("/api/admin/analytics/sessions")
                        .param("from", "2026-09-06").param("to", "2026-09-06")
                        .param("eventId", EVENT_E3.toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].revenueByCurrency.length()").value(2));

        mockMvc.perform(get("/api/admin/analytics/top")
                        .param("from", "2026-09-06").param("to", "2026-09-06")
                        .param("eventId", EVENT_E3.toString())
                        .param("metric", "NET_REVENUE").param("currency", "RON").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("RON"))
                .andExpect(jsonPath("$[0].value").value(10_000));
    }

    @Test
    @DisplayName("7.13 default range is the fixed-clock 30-day window")
    void defaultRangeIsThirtyDays() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-08-08"))
                .andExpect(jsonPath("$.to").value("2026-09-06"));
    }

    @Test
    @DisplayName("7.13 invalid and oversized ranges are rejected before any query")
    void invalidRangesRejected() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2026-09-06").param("to", "2026-08-08").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ANALYTICS_DATE_RANGE"));

        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2025-09-05").param("to", "2026-09-06").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ANALYTICS_DATE_RANGE_TOO_LARGE"));
    }

    @Test
    @DisplayName("7.13 zero denominators answer null ratios, never NaN")
    void zeroDenominatorRatiosAreNull() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/summary")
                        .param("from", "2020-01-01").param("to", "2020-01-02").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.created").value(0))
                .andExpect(jsonPath("$.rates.refund.ratio").doesNotExist())
                .andExpect(content().string(not(containsString("NaN"))));
    }

    @Test
    @DisplayName("7.13 financial sort/top without currency is rejected")
    void financialSortRequiresCurrency() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/sessions")
                        .param("from", "2026-09-06").param("to", "2026-09-06")
                        .param("sort", "grossRevenue,desc").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ANALYTICS_SORT"));

        mockMvc.perform(get("/api/admin/analytics/top")
                        .param("from", "2026-09-06").param("to", "2026-09-06")
                        .param("metric", "NET_REVENUE").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ANALYTICS_SORT"));
    }

    @Test
    @DisplayName("7.13 CSV export over HTTP carries exact content type, filename, and S4 grain")
    void csvExportOverHttp() throws Exception {
        mockMvc.perform(get("/api/admin/analytics/export/daily.csv")
                        .param("from", "2026-09-06").param("to", "2026-09-06")
                        .param("eventId", EVENT_E3.toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        containsString("seatflow-analytics-2026-09-06-2026-09-06.csv")))
                .andExpect(content().string(startsWith("row_type,metric_date,")))
                .andExpect(content().string(containsString("OPERATIONS")))
                .andExpect(content().string(containsString(",RON,")))
                .andExpect(content().string(containsString(",EUR,")))
                .andExpect(content().string(not(containsString("customerEmail"))));
    }

    @Test
    @DisplayName("7.13 no HTTP source-service test double backs these responses")
    void noSourceServiceHttpDependency() {
        // No WireMock (or any HTTP stub server) is on the test classpath, so a hidden
        // reservation/payment/ticket/event REST call could not have been stubbed: every
        // response above was rendered from the seatflow_analytics read model alone.
        assertThat(ClassUtils.isPresent(
                "com.github.tomakehurst.wiremock.WireMockServer",
                getClass().getClassLoader())).isFalse();
        assertThat(ClassUtils.isPresent(
                "org.mockserver.client.MockServerClient",
                getClass().getClassLoader())).isFalse();
    }
}
