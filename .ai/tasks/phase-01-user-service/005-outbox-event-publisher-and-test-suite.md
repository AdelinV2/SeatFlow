# TASK-P01-005: Outbox Event Publisher & Comprehensive Test Suite

## 1. Task Metadata
- **Task ID:** `TASK-P01-005`
- **Git Branch:** `feat/p01-005-outbox-event-publisher-and-test-suite`
- **Target Module:** `backend/services/user-service`
- **Phase:** `Phase 01 - User Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/05-messaging-and-outbox.md` (Section 3), `backend/AGENTS.md` (Section 7 & 10)
- **Related ADRs:** None
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the `OutboxEventPublisher` scheduled worker that polls unpublished events from `outbox_events` and publishes them to Kafka via `KafkaTemplate`. Additionally, implement the complete test suite covering unit tests (Mockito), repository slice tests (Testcontainers PostgreSQL), controller slice tests (`@WebMvcTest`), and end-to-end integration tests.

### Critical Invariants to Enforce:
- [ ] Outbox publisher uses `@Scheduled(fixedDelayString = ...)` — NEVER `@Scheduled(fixedRate = ...)`.
- [ ] Publisher sends events to `EventTopics.USER_EVENTS` (`seatflow.user.events`) topic.
- [ ] Publisher uses aggregate ID as Kafka message key for partition ordering.
- [ ] Failed Kafka sends increment `retry_count` — publisher does NOT throw and stop on individual failures.
- [ ] Events exceeding `max_retries` (5) are skipped by the publisher (enforced by DB constraint).
- [ ] Repository tests use Testcontainers PostgreSQL 16-alpine with `@DataJpaTest`.
- [ ] Controller tests use `@WebMvcTest` with `spring-security-test` MockJwt.
- [ ] No `@RestControllerAdvice` in test configuration — `GlobalExceptionHandler` is auto-configured.

---

## 3. Exact File Inventory

- `[NEW]` `src/main/java/com/seatflow/user/messaging/producer/OutboxEventPublisher.java`
- `[NEW]` `src/test/java/com/seatflow/user/service/UserServiceImplTest.java`
- `[NEW]` `src/test/java/com/seatflow/user/repository/UserRepositoryTest.java`
- `[NEW]` `src/test/java/com/seatflow/user/repository/OutboxEventRepositoryTest.java`
- `[NEW]` `src/test/java/com/seatflow/user/web/controller/UserControllerTest.java`
- `[NEW]` `src/test/java/com/seatflow/user/web/controller/AdminUserControllerTest.java`
- `[NEW]` `src/test/java/com/seatflow/user/integration/UserServiceIntegrationTest.java`

All paths relative to `backend/services/user-service/`.

---

## 4. Technical Specifications & Contracts

### 4.1 Outbox Event Publisher
```java
package com.seatflow.user.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.user.model.entity.OutboxEvent;
import com.seatflow.user.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

        if (events.isEmpty()) {
            return;
        }

        log.debug("Outbox publisher polling. unpublishedCount={}", events.size());

        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(
                    EventTopics.USER_EVENTS,
                    event.getAggregateId().toString(),
                    event.getPayload()
                );

                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);

                log.info("Outbox event published. outboxEventId={}, eventType={}, aggregateId={}, topic={}",
                        event.getId(), event.getEventType(), event.getAggregateId(), EventTopics.USER_EVENTS);

            } catch (Exception ex) {
                event.setRetryCount(event.getRetryCount() + 1);
                outboxEventRepository.save(event);

                log.error("Failed to publish outbox event. outboxEventId={}, eventType={}, aggregateId={}, retryCount={}",
                        event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount(), ex);
            }
        }
    }
}
```

### 4.2 Unit Test: `UserServiceImplTest`
```java
package com.seatflow.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.user.mapper.UserMapper;
import com.seatflow.user.model.entity.OutboxEvent;
import com.seatflow.user.model.entity.User;
import com.seatflow.user.repository.OutboxEventRepository;
import com.seatflow.user.repository.UserRepository;
import com.seatflow.user.service.impl.UserServiceImpl;
import com.seatflow.user.web.dto.request.UpdateUserProfileRequest;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @InjectMocks
    private UserServiceImpl userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private UserMapper userMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldReturnExistingUserWhenAlreadyProvisioned() {
        // Given
        String externalId = "ext-123";
        User existingUser = User.builder()
                .id(UUID.randomUUID())
                .externalId(externalId)
                .email("test@example.com")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        UserProfileResponse expectedResponse = new UserProfileResponse(
                existingUser.getId(), "test@example.com", null, existingUser.getCreatedAt());

        when(userRepository.findByExternalId(externalId)).thenReturn(Optional.of(existingUser));
        when(userMapper.toResponse(existingUser)).thenReturn(expectedResponse);

        // When
        UserProfileResponse result = userService.getOrCreateUserProfile(externalId, "test@example.com");

        // Then
        assertThat(result).isEqualTo(expectedResponse);
        verify(userRepository, never()).save(any(User.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldJitProvisionNewUserAndWriteOutboxEvent() {
        // Given
        String externalId = "ext-new";
        String email = "new@example.com";
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        User savedUser = User.builder()
                .id(userId)
                .externalId(externalId)
                .email(email)
                .createdAt(now)
                .updatedAt(now)
                .build();
        UserProfileResponse expectedResponse = new UserProfileResponse(
                userId, email, null, now);

        when(userRepository.findByExternalId(externalId)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(userMapper.toResponse(savedUser)).thenReturn(expectedResponse);

        // When
        UserProfileResponse result = userService.getOrCreateUserProfile(externalId, email);

        // Then
        assertThat(result).isEqualTo(expectedResponse);
        verify(userRepository).save(any(User.class));

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent capturedEvent = outboxCaptor.getValue();
        assertThat(capturedEvent.getEventType()).isEqualTo("UserRegistered");
        assertThat(capturedEvent.getAggregateId()).isEqualTo(userId);
        assertThat(capturedEvent.getPayload()).contains(email);
    }

    @Test
    void shouldUpdateExistingUserProfile() {
        // Given
        String externalId = "ext-update";
        User existingUser = User.builder()
                .id(UUID.randomUUID())
                .externalId(externalId)
                .email("update@example.com")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        UpdateUserProfileRequest request = new UpdateUserProfileRequest("+1-555-0199");

        when(userRepository.findByExternalId(externalId)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);
        when(userMapper.toResponse(existingUser)).thenReturn(
                new UserProfileResponse(existingUser.getId(), "update@example.com", "+1-555-0199", existingUser.getCreatedAt()));

        // When
        UserProfileResponse result = userService.updateUserProfile(externalId, "update@example.com", request);

        // Then
        assertThat(result.phone()).isEqualTo("+1-555-0199");
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldReturnPagedResultForAdminListUsers() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        User user1 = User.builder().id(UUID.randomUUID()).email("a@test.com").externalId("ext-a")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        User user2 = User.builder().id(UUID.randomUUID()).email("b@test.com").externalId("ext-b")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();

        var page = new PageImpl<>(List.of(user1, user2), pageable, 2);
        when(userRepository.findAll(pageable)).thenReturn(page);
        when(userMapper.toResponse(any(User.class))).thenReturn(
                new UserProfileResponse(UUID.randomUUID(), "test@test.com", null, Instant.now()));

        // When
        PagedResult<UserProfileResponse> result = userService.getAllUsers(pageable);

        // Then
        assertThat(result.content()).hasSize(2);
        assertThat(result.page()).isZero();
        assertThat(result.totalElements()).isEqualTo(2);
    }
}
```

### 4.3 Repository Slice Test: `UserRepositoryTest`
```java
package com.seatflow.user.repository;

import com.seatflow.user.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class UserRepositoryTest {

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
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndFindByExternalId() {
        User user = User.builder()
                .externalId("ext-repo-test")
                .email("repo-test@example.com")
                .phone("+1-555-0100")
                .build();

        User saved = userRepository.save(user);

        Optional<User> found = userRepository.findByExternalId("ext-repo-test");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getEmail()).isEqualTo("repo-test@example.com");
        assertThat(found.get().getPhone()).isEqualTo("+1-555-0100");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void shouldFindByEmail() {
        User user = User.builder()
                .externalId("ext-email-test")
                .email("email-test@example.com")
                .build();
        userRepository.save(user);

        Optional<User> found = userRepository.findByEmail("email-test@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getExternalId()).isEqualTo("ext-email-test");
    }

    @Test
    void shouldReturnEmptyForNonExistentExternalId() {
        Optional<User> found = userRepository.findByExternalId("non-existent");
        assertThat(found).isEmpty();
    }

    @Test
    void shouldCheckExistsByExternalId() {
        User user = User.builder()
                .externalId("ext-exists-test")
                .email("exists-test@example.com")
                .build();
        userRepository.save(user);

        assertThat(userRepository.existsByExternalId("ext-exists-test")).isTrue();
        assertThat(userRepository.existsByExternalId("non-existent")).isFalse();
    }
}
```

### 4.4 Repository Slice Test: `OutboxEventRepositoryTest`
```java
package com.seatflow.user.repository;

import com.seatflow.user.model.entity.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class OutboxEventRepositoryTest {

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
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void shouldFindUnpublishedEventsOrderedByCreatedAt() {
        OutboxEvent event1 = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("UserRegistered")
                .payload("{\"userId\":\"test\"}")
                .build();
        OutboxEvent event2 = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("UserRegistered")
                .payload("{\"userId\":\"test2\"}")
                .build();
        OutboxEvent publishedEvent = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("UserRegistered")
                .payload("{\"userId\":\"published\"}")
                .publishedAt(Instant.now())
                .build();

        outboxEventRepository.saveAll(List.of(event1, event2, publishedEvent));

        List<OutboxEvent> unpublished = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(unpublished).hasSize(2);
        assertThat(unpublished).noneMatch(e -> e.getPublishedAt() != null);
    }

    @Test
    void shouldReturnEmptyWhenAllEventsPublished() {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateId(UUID.randomUUID())
                .eventType("UserRegistered")
                .payload("{\"userId\":\"all-published\"}")
                .publishedAt(Instant.now())
                .build();
        outboxEventRepository.save(event);

        List<OutboxEvent> unpublished = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        assertThat(unpublished).isEmpty();
    }
}
```

### 4.5 Controller Slice Test: `UserControllerTest`
```java
package com.seatflow.user.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.user.service.UserService;
import com.seatflow.user.web.dto.request.UpdateUserProfileRequest;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @Test
    void shouldReturnUserProfileForAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UserProfileResponse response = new UserProfileResponse(
                userId, "test@example.com", "+1-555-0199", Instant.now());

        when(userService.getOrCreateUserProfile(anyString(), anyString()))
                .thenReturn(response);

        mockMvc.perform(get("/api/users/me")
                        .with(jwt().jwt(j -> j
                                .subject("ext-123")
                                .claim("email", "test@example.com")
                                .claim("roles", java.util.List.of("ROLE_CUSTOMER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.phone").value("+1-555-0199"));
    }

    @Test
    void shouldReject401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldUpdateUserProfileSuccessfully() throws Exception {
        UUID userId = UUID.randomUUID();
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("+1-555-0199");
        UserProfileResponse response = new UserProfileResponse(
                userId, "test@example.com", "+1-555-0199", Instant.now());

        when(userService.updateUserProfile(anyString(), anyString(), any(UpdateUserProfileRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/users/me")
                        .with(jwt().jwt(j -> j
                                .subject("ext-123")
                                .claim("email", "test@example.com")
                                .claim("roles", java.util.List.of("ROLE_CUSTOMER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+1-555-0199"));
    }
}
```

### 4.6 Controller Slice Test: `AdminUserControllerTest`
```java
package com.seatflow.user.web.controller;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.user.service.UserService;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserController.class)
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void shouldReturnPagedUsersForAdmin() throws Exception {
        UserProfileResponse user1 = new UserProfileResponse(
                UUID.randomUUID(), "admin@test.com", null, Instant.now());
        PagedResult<UserProfileResponse> result = PagedResult.of(List.of(user1), 0, 20, 1);

        when(userService.getAllUsers(any())).thenReturn(result);

        mockMvc.perform(get("/api/admin/users")
                        .with(jwt().jwt(j -> j
                                .subject("admin-ext")
                                .claim("email", "admin@test.com")
                                .claim("roles", List.of("ROLE_ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].email").value("admin@test.com"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldReject403ForNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .with(jwt().jwt(j -> j
                                .subject("user-ext")
                                .claim("email", "user@test.com")
                                .claim("roles", List.of("ROLE_CUSTOMER")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReject401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }
}
```

### 4.7 Integration Test: `UserServiceIntegrationTest`
```java
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.test.web.servlet.MockMvc;

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
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p01-005-outbox-event-publisher-and-test-suite develop`
2. **Step 2 — OutboxEventPublisher:** Create the scheduled component with `@Scheduled(fixedDelayString = ...)`, `KafkaTemplate`, and retry/error handling.
3. **Step 3 — UserServiceImplTest:** Create unit tests with Mockito for JIT provisioning, profile update, and admin pagination.
4. **Step 4 — UserRepositoryTest:** Create repository slice test with Testcontainers PostgreSQL.
5. **Step 5 — OutboxEventRepositoryTest:** Create repository slice test for outbox queries.
6. **Step 6 — UserControllerTest:** Create `@WebMvcTest` with MockJwt for `GET /api/users/me` and `PUT /api/users/me`.
7. **Step 7 — AdminUserControllerTest:** Create `@WebMvcTest` with MockJwt validating RBAC (admin allowed, customer rejected, unauthenticated rejected).
8. **Step 8 — UserServiceIntegrationTest:** Create `@SpringBootTest` end-to-end test with Testcontainers verifying JIT provisioning, outbox event creation, profile update, and authentication enforcement.
9. **Step 9 — Run Full Test Suite:** Execute verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean test -pl services/user-service -am
```
- [ ] All unit tests pass (Mockito): `UserServiceImplTest`.
- [ ] All repository slice tests pass (Testcontainers): `UserRepositoryTest`, `OutboxEventRepositoryTest`.
- [ ] All controller slice tests pass (`@WebMvcTest`): `UserControllerTest`, `AdminUserControllerTest`.
- [ ] Integration tests pass (`@SpringBootTest`): `UserServiceIntegrationTest`.
- [ ] `OutboxEventPublisher` compiles and uses `EventTopics.USER_EVENTS`.
- [ ] No `@RestControllerAdvice` created in this module.
- [ ] Task file is moved to `.ai/tasks/completed/phase-01-user-service/005-outbox-event-publisher-and-test-suite.md`.
