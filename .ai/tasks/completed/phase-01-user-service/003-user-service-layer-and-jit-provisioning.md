# TASK-P01-003: User Service Layer & JIT Provisioning Logic

## 1. Task Metadata
- **Task ID:** `TASK-P01-003`
- **Git Branch:** `feat/p01-003-user-service-layer-and-jit-provisioning`
- **Target Module:** `backend/services/user-service`
- **Phase:** `Phase 01 - User Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/02-microservices-spec.md` (Section 3), `.ai/architecture/04-authentication-security.md` (Section 2 & 3), `.ai/architecture/05-messaging-and-outbox.md` (Section 2.2), `.ai/architecture/06-api-contracts.md` (Section 2.1)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md` (Section 3.6)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the `UserService` interface and `UserServiceImpl` containing JIT (Just-In-Time) user provisioning logic, profile retrieval, profile update, and admin user pagination. Upon first-time JIT registration, atomically persist the user profile and write an `EventEnvelope<UserRegisteredEvent>` into the `outbox_events` table within the same database transaction.

### Critical Invariants to Enforce:
- [ ] JIT provisioning: If user with `external_id` (JWT `sub` claim) does not exist in `users` table, automatically create the profile (`email`, `externalId`) and write a serialized `EventEnvelope<UserRegisteredEvent>` to `outbox_events` in the SAME transaction.
- [ ] No direct Kafka publishing — all domain events committed to `outbox_events` table (Transactional Outbox Pattern).
- [ ] Outbox payload is wrapped in `EventEnvelope<T>` from `common-events` with correlation context propagated from `CorrelationContext`.
- [ ] `UserRegisteredEvent` enables downstream services (ticket-service, reservation-service) to claim historical guest orders per ADR-001.
- [ ] Admin pagination uses `PagedResult<T>` from `common-domain`.
- [ ] Profile update only modifies `phone` — never `email` or `externalId`.
- [ ] All service methods use structured contextual logging with domain identifiers.

---

## 3. Exact File Inventory

- `[NEW]` `src/main/java/com/seatflow/user/service/UserService.java`
- `[NEW]` `src/main/java/com/seatflow/user/service/impl/UserServiceImpl.java`

All paths relative to `backend/services/user-service/`.

---

## 4. Technical Specifications & Contracts

### 4.1 Service Interface: `UserService`
```java
package com.seatflow.user.service;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.user.web.dto.request.UpdateUserProfileRequest;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.springframework.data.domain.Pageable;

public interface UserService {

    /**
     * Retrieve the current user's profile. If the user does not exist in the database
     * (first authenticated request), perform JIT provisioning: create the user profile
     * from JWT claims and atomically write an EventEnvelope<UserRegisteredEvent> to outbox_events.
     *
     * @param externalId The JWT 'sub' claim (external identity provider subject ID)
     * @param email      The JWT 'email' claim
     * @return UserProfileResponse
     */
    UserProfileResponse getOrCreateUserProfile(String externalId, String email);

    /**
     * Update the current user's profile (phone).
     * If the user does not exist, perform JIT provisioning first.
     *
     * @param externalId The JWT 'sub' claim
     * @param email      The JWT 'email' claim
     * @param request    UpdateUserProfileRequest with new profile fields
     * @return Updated UserProfileResponse
     */
    UserProfileResponse updateUserProfile(String externalId, String email, UpdateUserProfileRequest request);

    /**
     * Admin-only: List all registered users with pagination.
     *
     * @param pageable Spring Data Pageable (page, size, sort)
     * @return PagedResult<UserProfileResponse>
     */
    PagedResult<UserProfileResponse> getAllUsers(Pageable pageable);
}
```

### 4.2 Service Implementation: `UserServiceImpl`
```java
package com.seatflow.user.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.user.mapper.UserMapper;
import com.seatflow.user.messaging.event.UserRegisteredEvent;
import com.seatflow.user.model.entity.OutboxEvent;
import com.seatflow.user.model.entity.User;
import com.seatflow.user.repository.OutboxEventRepository;
import com.seatflow.user.repository.UserRepository;
import com.seatflow.user.service.UserService;
import com.seatflow.user.web.dto.request.UpdateUserProfileRequest;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public UserProfileResponse getOrCreateUserProfile(String externalId, String email) {
        log.debug("Resolving user profile. externalId={}, email={}", externalId, email);

        return userRepository.findByExternalId(externalId)
                .map(existingUser -> {
                    log.debug("Existing user found. userId={}, externalId={}", existingUser.getId(), externalId);
                    return userMapper.toResponse(existingUser);
                })
                .orElseGet(() -> {
                    log.info("JIT provisioning new user. externalId={}, email={}", externalId, email);
                    User newUser = createUserFromJwtClaims(externalId, email);
                    writeUserRegisteredOutboxEvent(newUser);
                    return userMapper.toResponse(newUser);
                });
    }

    @Override
    @Transactional
    public UserProfileResponse updateUserProfile(String externalId, String email, UpdateUserProfileRequest request) {
        User user = userRepository.findByExternalId(externalId)
                .orElseGet(() -> {
                    log.info("JIT provisioning on profile update. externalId={}, email={}", externalId, email);
                    User newUser = createUserFromJwtClaims(externalId, email);
                    writeUserRegisteredOutboxEvent(newUser);
                    return newUser;
                });

        if (request.phone() != null) {
            user.setPhone(request.phone());
        }

        User updatedUser = userRepository.save(user);
        log.info("User profile updated. userId={}, externalId={}", updatedUser.getId(), externalId);
        return userMapper.toResponse(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<UserProfileResponse> getAllUsers(Pageable pageable) {
        Page<User> page = userRepository.findAll(pageable);
        var content = page.getContent().stream()
                .map(userMapper::toResponse)
                .toList();
        return PagedResult.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    // ---- Private Helpers ----

    private User createUserFromJwtClaims(String externalId, String email) {
        User user = User.builder()
                .externalId(externalId)
                .email(email)
                .build();

        return userRepository.save(user);
    }

    private void writeUserRegisteredOutboxEvent(User user) {
        UserRegisteredEvent eventPayload = new UserRegisteredEvent(
                user.getId(),
                user.getEmail(),
                user.getCreatedAt()
        );

        String correlationId = CorrelationContext.getCorrelationId().orElse(UUID.randomUUID().toString());

        EventEnvelope<UserRegisteredEvent> envelope = EventEnvelope.of(
                "UserRegistered",
                user.getId().toString(),
                correlationId,
                eventPayload
        );

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize EventEnvelope<UserRegisteredEvent>. userId={}, email={}", user.getId(), user.getEmail(), e);
            throw new BusinessException(
                    "Failed to serialize domain event envelope",
                    e,
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    500
            );
        }

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateId(user.getId())
                .eventType("UserRegistered")
                .payload(payloadJson)
                .build();

        outboxEventRepository.save(outboxEvent);
        log.info("UserRegisteredEvent written to outbox. userId={}, email={}, outboxEventId={}",
                user.getId(), user.getEmail(), outboxEvent.getId());
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p01-003-user-service-layer-and-jit-provisioning develop`
2. **Step 2 — Service Interface:** Create `UserService.java` with `getOrCreateUserProfile`, `updateUserProfile`, `getAllUsers` methods.
3. **Step 3 — Service Implementation:** Create `UserServiceImpl.java` with JIT provisioning logic, `EventEnvelope<UserRegisteredEvent>` outbox serialization, profile update, and admin pagination.
4. **Step 4 — Verify Compilation:** Run `mvn clean compile -pl services/user-service -am` from `backend/`.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean compile -pl services/user-service -am
```
- [ ] `UserService` interface and `UserServiceImpl` compile cleanly.
- [ ] JIT provisioning creates user and writes `EventEnvelope<UserRegisteredEvent>` to `outbox_events` in the same transaction.
- [ ] `PagedResult.of(...)` used for admin pagination.
- [ ] Structured logging with domain identifiers on all business events.
- [ ] No direct Kafka publishing — all events go to outbox table.
- [ ] Task file is moved to `.ai/tasks/completed/phase-01-user-service/003-user-service-layer-and-jit-provisioning.md`.
