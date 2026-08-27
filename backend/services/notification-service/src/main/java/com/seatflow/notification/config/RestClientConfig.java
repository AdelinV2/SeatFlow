package com.seatflow.notification.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * Plain (non-load-balanced) RestClient.Builder.
     *
     * <p>Must be {@code @Primary}: Eureka Client itself uses a RestClient to talk to the
     * Eureka Server, and external HTTP clients (such as the Resend Email Client) require
     * standard URL resolution. If the only RestClient.Builder in the context were load-balanced,
     * Eureka would try to resolve its own server URL through the load balancer and fail to register.
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Load-balanced RestClient.Builder used for inter-service calls resolved via Eureka +
     * Spring Cloud LoadBalancer (e.g. {@code http://ticket-service}).
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder ticketServiceLoadBalancedBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * Resend REST Client builder configured with connect and read timeouts.
     */
    @Bean
    public RestClient.Builder resendRestClientBuilder(ResendProperties resendProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(resendProperties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(resendProperties.getReadTimeoutMs()));
        return RestClient.builder().requestFactory(requestFactory);
    }
}
