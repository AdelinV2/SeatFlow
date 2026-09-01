package com.seatflow.common.observability.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusConfigurationContractTest {

    private static final Pattern TARGET = Pattern.compile("['\"]([^'\"]+):(\\d+)['\"]");

    @Test
    void scrapeTargetsShouldMatchComposeApplicationTopology() throws IOException {
        Path repository = locateRepositoryRoot();
        String prometheus = Files.readString(repository.resolve("docker/prometheus/prometheus.yml"));
        String compose = Files.readString(repository.resolve("docker/docker-compose.yml"))
                + Files.readString(repository.resolve("docker/docker-compose.monitoring.yml"));

        Matcher matcher = TARGET.matcher(prometheus);
        Set<String> allowedHosts = Set.of(
                "localhost",
                "host.docker.internal",
                "eureka-server",
                "api-gateway",
                "user-service",
                "seat-map-service",
                "event-service",
                "reservation-service",
                "payment-service",
                "ticket-service",
                "realtime-service",
                "notification-service",
                "kafka-exporter",
                "otel-collector",
                "tempo",
                "loki");
        int targetCount = 0;
        while (matcher.find()) {
            assertThat(matcher.group(1)).isIn(allowedHosts);
            targetCount++;
        }

        assertThat(targetCount).isEqualTo(15);
        assertThat(prometheus).contains("eureka-server:8761");
        assertThat(prometheus).contains(
                "regex: 'seatflow_reservations_created_events_total'",
                "replacement: 'seatflow_reservations_created_total'");
        assertThat(countOccurrences(prometheus, "credentials_file: /run/secrets/prometheus-scrape-token"))
                .isEqualTo(3);
        assertThat(compose).contains(
                "${PROMETHEUS_SCRAPE_TOKEN_FILE:-./prometheus/prometheus-scrape-token.example}:/run/secrets/prometheus-scrape-token:ro");
    }

    @Test
    void acceptanceQueriesShouldUseStablePrometheusMetricNames() throws IOException {
        String queries = Files.readString(locateRepositoryRoot()
                .resolve("docker/prometheus/acceptance-queries.md"));

        assertThat(queries).contains(
                "http_server_requests_seconds_count",
                "http_server_requests_seconds_bucket",
                "seatflow_reservations_conflicts_total",
                "seatflow_outbox_publish_latency_seconds_bucket");
    }

    @Test
    void everyApplicationShouldEnforceTheActuatorAuthorizationContract() throws IOException {
        Path repository = locateRepositoryRoot();
        List<Path> securityConfigurations = List.of(
                repository.resolve("backend/services/api-gateway/src/main/java/com/seatflow/gateway/config/GatewaySecurityConfig.java"),
                repository.resolve("backend/services/eureka-server/src/main/java/com/seatflow/eureka/config/SecurityConfig.java"),
                repository.resolve("backend/services/event-service/src/main/java/com/seatflow/event/config/SecurityConfig.java"),
                repository.resolve("backend/services/notification-service/src/main/java/com/seatflow/notification/config/SecurityConfig.java"),
                repository.resolve("backend/services/payment-service/src/main/java/com/seatflow/payment/config/SecurityConfig.java"),
                repository.resolve("backend/services/realtime-service/src/main/java/com/seatflow/realtime/config/SecurityConfig.java"),
                repository.resolve("backend/services/reservation-service/src/main/java/com/seatflow/reservation/config/SecurityConfig.java"),
                repository.resolve("backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/config/SecurityConfig.java"),
                repository.resolve("backend/services/ticket-service/src/main/java/com/seatflow/ticket/config/SecurityConfig.java"),
                repository.resolve("backend/services/user-service/src/main/java/com/seatflow/user/config/SecurityConfig.java")
        );

        assertThat(securityConfigurations).allSatisfy(path -> {
            assertThat(path).exists();
            String source = Files.readString(path);
            assertThat(source)
                    .contains("/actuator/prometheus", "SCOPE_metrics.read", "/actuator/metrics", "hasRole(\"ADMIN\")")
                    .doesNotContain("/actuator/prometheus\", \"/actuator/metrics\").permitAll()",
                            "/actuator/info\", \"/actuator/prometheus");
        });
    }

    private static int countOccurrences(String input, String expected) {
        return (input.length() - input.replace(expected, "").length()) / expected.length();
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("docker/prometheus/prometheus.yml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate SeatFlow repository root");
        }
        return current;
    }
}
