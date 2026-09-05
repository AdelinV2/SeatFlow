package com.seatflow.common.security.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupabaseAudienceConfigurationContractTest {

    private static final String EXPECTED_AUDIENCE = "authenticated";
    private static final String LEGACY_ENTRA_AUDIENCE = "api://seatflow-backend";

    @Test
    void serviceEnvironmentTemplatesShouldUseSupabaseAudience() throws IOException {
        Path repository = locateRepositoryRoot();
        List<Path> environmentTemplates = List.of(
                repository.resolve("backend/services/api-gateway/.env.example"),
                repository.resolve("backend/services/event-service/.env.example"),
                repository.resolve("backend/services/notification-service/.env.example"),
                repository.resolve("backend/services/payment-service/.env.example"),
                repository.resolve("backend/services/realtime-service/.env.example"),
                repository.resolve("backend/services/reservation-service/.env.example"),
                repository.resolve("backend/services/seat-map-service/.env.example"),
                repository.resolve("backend/services/ticket-service/.env.example"),
                repository.resolve("backend/services/user-service/.env.example")
        );

        assertThat(environmentTemplates).allSatisfy(path -> {
            assertThat(path).exists();
            String content = Files.readString(path);
            assertThat(content)
                    .contains("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=" + EXPECTED_AUDIENCE)
                    .doesNotContain(LEGACY_ENTRA_AUDIENCE);
        });
    }

    @Test
    void dockerProfileDefaultsShouldUseSupabaseAudience() throws IOException {
        Path repository = locateRepositoryRoot();
        List<Path> dockerConfigurations = List.of(
                repository.resolve("backend/services/event-service/src/main/resources/application-docker.yaml"),
                repository.resolve("backend/services/payment-service/src/main/resources/application-docker.yaml"),
                repository.resolve("backend/services/reservation-service/src/main/resources/application-docker.yaml"),
                repository.resolve("backend/services/seat-map-service/src/main/resources/application-docker.yaml"),
                repository.resolve("backend/services/ticket-service/src/main/resources/application-docker.yaml"),
                repository.resolve("backend/services/user-service/src/main/resources/application-docker.yaml")
        );

        assertThat(dockerConfigurations).allSatisfy(path -> {
            assertThat(path).exists();
            String content = Files.readString(path);
            assertThat(content)
                    .contains("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES:" + EXPECTED_AUDIENCE + "}")
                    .doesNotContain(LEGACY_ENTRA_AUDIENCE);
        });
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
