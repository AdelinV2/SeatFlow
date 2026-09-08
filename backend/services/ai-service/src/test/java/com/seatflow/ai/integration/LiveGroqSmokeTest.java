package com.seatflow.ai.integration;

import com.seatflow.ai.service.AiFeatureState;
import com.seatflow.ai.service.AiStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live Groq smoke test (TASK-P15-007 section 8). Never runs in default CI.
 *
 * <p>Gating (all required):
 * <ul>
 *   <li>{@code AI_LIVE_SMOKE=true} — explicit opt-in, so a stray {@code GROQ_API_KEY} in the
 *       environment can never trigger provider spend on its own;</li>
 *   <li>{@code AI_ENABLED=true};</li>
 *   <li>{@code GROQ_API_KEY} present (real key, never committed);</li>
 *   <li>JUnit tag {@code live}, excluded from default CI via surefire
 *       {@code excludedGroups=live} in {@code backend/pom.xml}.</li>
 * </ul>
 *
 * <p>Run explicitly (small free-tier usage, one short provider call, no tools, no reservation).
 * {@code -Dseatflow.excluded.groups=} is required: it clears the default surefire
 * {@code excludedGroups} exclusion (property {@code seatflow.excluded.groups}, default
 * {@code live} in {@code backend/pom.xml}; {@code -Dgroups=live} alone selects zero tests):
 * <pre>
 *   AI_LIVE_SMOKE=true AI_ENABLED=true GROQ_API_KEY=&lt;secret&gt; \
 *     GROQ_BASE_URL=https://api.groq.com/openai/v1 GROQ_MODEL=openai/gpt-oss-20b \
 *     mvn -pl services/ai-service test -Dgroups=live -Dseatflow.excluded.groups= -Dtest=LiveGroqSmokeTest
 * </pre>
 *
 * <p>Required manual scenario after this automated ping passes (no real-money processing):
 * <ol>
 *   <li>start SeatFlow with seeded event/session/seat data;</li>
 *   <li>authenticate as a normal USER;</li>
 *   <li>ask the assistant for a known event and a small seat constraint;</li>
 *   <li>verify Groq chooses/uses the expected read-only tools;</li>
 *   <li>verify the structured proposal matches live SeatFlow data;</li>
 *   <li>click explicit confirmation;</li>
 *   <li>verify one normal reservation hold is created with authoritative expiry;</li>
 *   <li>continue to the existing checkout UI without real-money processing.</li>
 * </ol>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "AI_LIVE_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "AI_ENABLED", matches = "true")
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
@SpringBootTest(properties = "spring.ai.openai.chat.max-completion-tokens=50")
@ActiveProfiles("test")
@MockitoBean(types = JwtDecoder.class)
class LiveGroqSmokeTest {

    private final AiStatusService statusService;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

    @Autowired
    LiveGroqSmokeTest(AiStatusService statusService,
                      ObjectProvider<ChatClient.Builder> chatClientBuilder) {
        this.statusService = statusService;
        this.chatClientBuilder = chatClientBuilder;
    }

    @Test
    @DisplayName("live provider ping: READY state plus one tiny safe Groq response")
    void liveProviderPing() {
        assertThat(statusService.currentState()).isEqualTo(AiFeatureState.READY);
        assertThat(statusService.isChatAvailable()).isTrue();

        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        assertThat(builder).as("ChatClient builder available with a live key").isNotNull();
        String content = builder.build().prompt()
                .system("Reply with exactly the word READY and nothing else.")
                .user("ping")
                .call()
                .content();

        assertThat(content).as("live provider answered").isNotBlank();
        assertThat(content)
                .doesNotContain("gsk_")
                .doesNotContain("GROQ_API_KEY");
    }
}
