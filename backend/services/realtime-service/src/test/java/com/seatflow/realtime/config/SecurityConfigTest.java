package com.seatflow.realtime.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Should permit unauthenticated access to /ws endpoint handshake")
    void wsHandshakeEndpoint_PermittedWithoutAuth() throws Exception {
        mockMvc.perform(get("/ws/info"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should permit unauthenticated access to actuator health")
    void actuatorHealth_PermittedWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorPrometheus_RequiresMetricsScope() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").with(jwt()
                        .authorities(new SimpleGrantedAuthority("SCOPE_metrics.read"))))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorMetrics_RequiresAdminRole() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(jwt()
                        .authorities(new SimpleGrantedAuthority("SCOPE_metrics.read"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }
}
