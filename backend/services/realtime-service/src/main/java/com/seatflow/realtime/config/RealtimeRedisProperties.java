package com.seatflow.realtime.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("seatflow.realtime.redis")
public record RealtimeRedisProperties(@NotBlank String channel) {
}
