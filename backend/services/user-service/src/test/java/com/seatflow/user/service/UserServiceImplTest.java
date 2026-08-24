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
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(userMapper.toResponse(savedUser)).thenReturn(expectedResponse);

        // When
        UserProfileResponse result = userService.getOrCreateUserProfile(externalId, email);

        // Then
        assertThat(result).isEqualTo(expectedResponse);
        verify(userRepository).saveAndFlush(any(User.class));

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
