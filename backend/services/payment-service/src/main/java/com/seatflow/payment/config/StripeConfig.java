package com.seatflow.payment.config;

import com.stripe.StripeClient;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class StripeConfig {

    @Value("${stripe.api-key:sk_test_dummy_key}")
    private String apiKey;

    @Value("${stripe.webhook-secret:whsec_dummy_secret}")
    private String webhookSecret;

    @Bean
    public StripeClient stripeClient(StripeConfig config) {
        return new StripeClient(config.getApiKey());
    }

}
