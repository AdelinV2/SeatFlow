package com.seatflow.user.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.user.model.entity.OutboxEvent;
import com.seatflow.user.model.entity.User;
import com.seatflow.user.repository.OutboxEventRepository;
import com.seatflow.user.repository.UserRepository;
import com.seatflow.user.web.dto.request.UpdateUserProfileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class UserServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_user_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void shouldJitProvisionUserOnFirstGetMeRequest() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .with(jwt().jwt(j -> j
                                .subject("integration-ext-001")
                                .claim("email", "integration@example.com")
                                .claim("roles", List.of("ROLE_CUSTOMER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("integration@example.com"));

        // Verify user persisted in database
        assertThat(userRepository.findByExternalId("integration-ext-001")).isPresent();

        // Verify UserRegisteredEvent in outbox
        List<OutboxEvent> outboxEvents = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.getFirst().getEventType()).isEqualTo("UserRegistered");
    }

    @Test
    void shouldReturnExistingUserOnSubsequentGetMeRequests() throws Exception {
        // First call — JIT provisions
        mockMvc.perform(get("/api/users/me")
                        .with(jwt().jwt(j -> j
                                .subject("integration-ext-002")
                                .claim("email", "subsequent@example.com")
                                .claim("roles", List.of("ROLE_CUSTOMER")))))
                .andExpect(status().isOk());

        // Second call — returns existing
        mockMvc.perform(get("/api/users/me")
                        .with(jwt().jwt(j -> j
                                .subject("integration-ext-002")
                                .claim("email", "subsequent@example.com")
                                .claim("roles", List.of("ROLE_CUSTOMER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("subsequent@example.com"));

        // Only ONE outbox event (from initial provisioning)
        List<OutboxEvent> outboxEvents = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(outboxEvents).hasSize(1);
    }

    @Test
    void shouldUpdateUserProfileViaPut() throws Exception {
        // JIT provision first
        mockMvc.perform(get("/api/users/me")
                        .with(jwt().jwt(j -> j
                                .subject("integration-ext-003")
                                .claim("email", "update-int@example.com")
                                .claim("roles", List.of("ROLE_CUSTOMER")))))
                .andExpect(status().isOk());

        // Update profile
        UpdateUserProfileRequest updateRequest = new UpdateUserProfileRequest("+1-555-9999");

        mockMvc.perform(put("/api/users/me")
                        .with(jwt().jwt(j -> j
                                .subject("integration-ext-003")
                                .claim("email", "update-int@example.com")
                                .claim("roles", List.of("ROLE_CUSTOMER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+1-555-9999"));

        // Verify database state
        User user = userRepository.findByExternalId("integration-ext-003").orElseThrow();
        assertThat(user.getPhone()).isEqualTo("+1-555-9999");
    }

    @Test
    void shouldRejectUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
