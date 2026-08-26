package com.seatflow.reservation.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Plain (non-load-balanced) RestClient.Builder.
     *
     * <p>Must be {@code @Primary}: Eureka Client itself uses a RestClient to talk to the
     * Eureka Server. If the only RestClient.Builder in the context were load-balanced, Eureka
     * would try to resolve the Eureka Server URL as a service instance and fail to register.
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Load-balanced RestClient.Builder used for inter-service calls resolved via Eureka +
     * Spring Cloud LoadBalancer (host = registered service name, e.g. {@code http://event-service}).
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder eventServiceLoadBalancedBuilder() {
        return RestClient.builder();
    }
}
