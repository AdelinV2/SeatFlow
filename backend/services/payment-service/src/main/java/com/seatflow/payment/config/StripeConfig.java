package com.seatflow.payment.config;

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

}
