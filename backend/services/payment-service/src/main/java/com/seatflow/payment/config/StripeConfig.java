package com.seatflow.payment.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class StripeConfig {

    @Value("${stripe.api-key:sk_test_dummy_key}")
    private String apiKey;

    @Value("${stripe.webhook-secret:whsec_dummy_secret}")
    private String webhookSecret;

    @PostConstruct
    public void init() {
        Stripe.apiKey = this.apiKey;
    }
}
