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

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private static final String USER_REGISTERED_EVENT = "UserRegistered";

    private final UserRepository userRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public UserProfileResponse getOrCreateUserProfile(String externalId, String email) {
        return userMapper.toResponse(getOrProvisionUser(externalId, email));
    }

    @Override
    @Transactional
    public UserProfileResponse updateUserProfile(String externalId, String email, UpdateUserProfileRequest request) {
        User user = getOrProvisionUser(externalId, email);

        if (request.phone() == null) {
            log.debug("No profile changes requested. userId={}, externalId={}", user.getId(), externalId);
            return userMapper.toResponse(user);
        }

        user.setPhone(request.phone());
        User updatedUser = userRepository.save(user);
        log.info("User profile updated. userId={}, externalId={}", updatedUser.getId(), externalId);
        return userMapper.toResponse(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<UserProfileResponse> getAllUsers(Pageable pageable) {
        Page<User> page = userRepository.findAll(pageable);
        List<UserProfileResponse> content = page.getContent().stream()
                .map(userMapper::toResponse)
                .toList();
        return PagedResult.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    // ---- Private Helpers ----

    private User getOrProvisionUser(String externalId, String email) {
        return userRepository.findByExternalId(externalId)
                .map(user -> {
                    log.debug("Existing user resolved. userId={}, externalId={}", user.getId(), externalId);
                    return user;
                })
                .orElseGet(() -> {
                    log.info("JIT provisioning new user. externalId={}, email={}", externalId, email);
                    User newUser = createUserFromJwtClaims(externalId, email);
                    writeUserRegisteredOutboxEvent(newUser);
                    return newUser;
                });
    }

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

        String correlationId = CorrelationContext.getCorrelationId()
                .orElseGet(() -> UUID.randomUUID().toString());

        EventEnvelope<UserRegisteredEvent> envelope = EventEnvelope.of(
                USER_REGISTERED_EVENT,
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
                .eventType(USER_REGISTERED_EVENT)
                .payload(payloadJson)
                .build();

        outboxEventRepository.save(outboxEvent);
        log.debug("UserRegisteredEvent written to outbox. userId={}, email={}, outboxId={}",
                user.getId(), user.getEmail(), outboxEvent.getId());
    }
}
