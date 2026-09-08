package com.seatflow.ai.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Eureka + LoadBalancer REST client pattern (constitution invariant 10).
 *
 * <p>The mandatory {@code @Primary} plain builder preserves Eureka client registration; the
 * {@code @LoadBalanced} builder is reserved for later P15 downstream tool calls to
 * {@code http://event-service}, {@code http://reservation-service}, etc. No downstream client exists
 * in P15-001; this establishes the pattern without hardcoding hosts/ports.
 */
@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
