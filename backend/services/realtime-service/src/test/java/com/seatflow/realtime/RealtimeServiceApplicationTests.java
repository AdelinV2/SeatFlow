package com.seatflow.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class RealtimeServiceApplicationTests {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Should load Spring application context cleanly without relational database")
    void contextLoads() {
        assertTrue(true, "Application context loaded successfully");
    }
}
