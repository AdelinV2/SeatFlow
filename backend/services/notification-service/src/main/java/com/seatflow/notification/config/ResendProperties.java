package com.seatflow.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "seatflow.resend")
public class ResendProperties {

    private String apiKey = "re_dummy_test_key";
    private String fromEmail = "SeatFlow <onboarding@resend.dev>";
    private String apiUrl = "https://api.resend.com";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
}
