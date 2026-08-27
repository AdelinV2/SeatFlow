package com.seatflow.ticket.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Un-annotated @Primary RestClient.Builder.
     * CRITICAL: Required for Eureka Client internal registration without LoadBalancer recursion.
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * @LoadBalanced RestClient.Builder for inter-service communication across microservices.
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
