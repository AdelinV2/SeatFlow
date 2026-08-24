package com.seatflow.user.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.user.config.SecurityConfig;
import com.seatflow.user.service.UserService;
import com.seatflow.user.web.dto.request.UpdateUserProfileRequest;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

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
