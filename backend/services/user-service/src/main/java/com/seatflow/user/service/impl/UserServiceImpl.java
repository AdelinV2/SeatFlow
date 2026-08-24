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

        return userRepository.saveAndFlush(user);
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
