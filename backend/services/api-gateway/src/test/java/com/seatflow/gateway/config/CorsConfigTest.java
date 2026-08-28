package com.seatflow.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "seatflow.cors.allowed-origins=http://localhost:4200,http://localhost:3000"
})
class CorsConfigTest {

    @Autowired
    private CorsWebFilter corsWebFilter;

    @Test
    @DisplayName("Verify CorsWebFilter bean is registered in ApplicationContext")
    void corsWebFilterBeanIsConfigured() {
        assertThat(corsWebFilter).isNotNull();
    }
}
