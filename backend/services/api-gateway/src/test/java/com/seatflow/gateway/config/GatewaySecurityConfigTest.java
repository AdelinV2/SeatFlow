package com.seatflow.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
                "spring.data.redis.repositories.enabled=false"
        })
class GatewaySecurityConfigTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    void actuatorHealthShouldRemainAnonymous() {
        client().get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void prometheusShouldRequireMetricsScope() {
        client().get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();

        stubToken("metrics-token", List.of(), "metrics.read");
        client("metrics-token")
                .get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }

    @Test
    void diagnosticMetricsShouldRequireAdministrator() {
        stubToken("metrics-token", List.of(), "metrics.read");
        client("metrics-token")
                .get().uri("/actuator/metrics").exchange().expectStatus().isForbidden();

        stubToken("admin-token", List.of("ADMIN"), "");
        client("admin-token")
                .get().uri("/actuator/metrics").exchange().expectStatus().isOk();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private WebTestClient client(String token) {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    private void stubToken(String token, List<String> roles, String scope) {
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("test-subject")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", roles)
                .claim("scope", scope)
                .build();
        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
    }
}
