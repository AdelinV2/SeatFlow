package com.seatflow.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class RealtimeServiceApplicationTests {

    @Test
    @DisplayName("Should load Spring application context cleanly without relational database")
    void contextLoads() {
        assertTrue(true, "Application context loaded successfully");
    }
}
