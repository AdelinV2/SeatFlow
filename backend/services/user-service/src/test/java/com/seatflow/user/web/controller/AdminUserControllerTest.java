package com.seatflow.user.web.controller;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.user.config.SecurityConfig;
import com.seatflow.user.service.UserService;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserController.class)
@Import(SecurityConfig.class)
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    @Test
    void shouldReturnPagedUsersForAdmin() throws Exception {
        UserProfileResponse user1 = new UserProfileResponse(
                UUID.randomUUID(), "admin@test.com", null, Instant.now());
        PagedResult<UserProfileResponse> result = PagedResult.of(List.of(user1), 0, 20, 1);

        when(userService.getAllUsers(any())).thenReturn(result);

        mockMvc.perform(get("/api/admin/users")
                        .with(user("admin").roles(SecurityRoles.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].email").value("admin@test.com"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldReject403ForNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .with(user("customer").roles(SecurityRoles.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReject401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }
}
