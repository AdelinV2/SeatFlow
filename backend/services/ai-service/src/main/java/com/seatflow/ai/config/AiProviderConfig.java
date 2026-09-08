package com.seatflow.ai.config;

import com.seatflow.ai.ratelimit.AiRateLimitProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Groq provider wiring foundations (TASK-P15-001).
 *
 * <p>Spring AI 2.0.1 properties in {@code application*.yaml} map {@code GROQ_BASE_URL},
 * {@code GROQ_API_KEY}, and {@code GROQ_MODEL} onto the OpenAI-compatible client. This config class
 * intentionally does <em>not</em> create a chat bean that would fail startup when the key is absent.
 * Startup safety is verified by tests: {@code AI_ENABLED=false} without a key starts, and
 * {@code AI_ENABLED=true} with a blank key starts and reports {@code MISCONFIGURED}.
 *
 * <p>Groq compatibility: {@code spring.ai.openai.chat.extra-body.reasoning_format=hidden}
 * in {@code application.yaml}. Without it, gpt-oss reasoning is echoed back on tool follow-up
 * turns and Groq rejects the request
 * (400: property {@code reasoning_content} is unsupported for role assistant), which surfaces
 * as a generic provider outage after tools already ran. {@code hidden} also upholds the
 * never-expose-reasoning invariant.
 *
 * <p>Interactive timeout policy for this phase (no automatic retry:
 * {@code spring.ai.openai.max-retries=0} in {@code application.yaml}):
 * <ul>
 *   <li>no retry for {@code 400/401/403/404};</li>
 *   <li>{@code 429} fails fast to {@code RATE_LIMITED} (no retry storm);</li>
 *   <li>no automatic retry for transient network/5xx/timeout failures; callers fail fast to
 *       {@code PROVIDER_UNAVAILABLE} (HTTP 503);</li>
 *   <li>explicit request timeout (see {@code spring.ai.openai.timeout}).</li>
 * </ul>
 * Full tool-calling chat belongs to later P15 tasks; this task only establishes the safe defaults.
 */
@Configuration
@EnableConfigurationProperties({AiFeatureProperties.class, AiRateLimitProperties.class})
public class AiProviderConfig {

    @Bean
    public AiProviderTimeouts aiProviderTimeouts(
            @Value("${spring.ai.openai.timeout:15s}") Duration timeout) {
        return new AiProviderTimeouts(timeout);
    }

    public record AiProviderTimeouts(Duration requestTimeout) {
    }
}
