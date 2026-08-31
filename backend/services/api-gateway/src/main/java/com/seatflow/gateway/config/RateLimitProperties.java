package com.seatflow.gateway.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties("seatflow.rate-limit")
public record RateLimitProperties(
        @Min(1) int replenishRate,
        @Min(1) long burstCapacity,
        @Min(1) int requestedTokens,
        List<String> trustedProxyCidrs
) {
    public RateLimitProperties {
        if (burstCapacity < replenishRate) {
            throw new IllegalArgumentException("burstCapacity must be greater than or equal to replenishRate");
        }
        if (requestedTokens > burstCapacity) {
            throw new IllegalArgumentException("requestedTokens must be less than or equal to burstCapacity");
        }
        trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
    }
}
