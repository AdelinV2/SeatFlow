package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import com.seatflow.ai.service.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Blank-content resilience: a transient empty completion (stop with no text and no tool
 * calls) is retried exactly once on the same request; only a repeated blank becomes
 * {@code AI_RESPONSE_INVALID}. Rate-limit failures throw before any retry is possible.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("deprecation")
class ChatClientAssistantModelClientTest {

    @Mock
    private ObjectProvider<ChatClient.Builder> builderProvider;
    @Mock
    private ChatClient.Builder builder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callSpec;
    @Mock
    private AssistantToolRegistry toolRegistry;

    private SimpleMeterRegistry registry;
    private ChatClientAssistantModelClient client;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(builderProvider.getIfAvailable()).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(toolRegistry.ordinaryChatToolCallbacks()).thenReturn(List.of());
        client = new ChatClientAssistantModelClient(builderProvider, toolRegistry,
                new AssistantProviderErrorMapper(),
                new AiMetrics(registry));
    }

    private AssistantModelClient.ModelTurnRequest request() {
        return new AssistantModelClient.ModelTurnRequest("system", List.of(), "Find events this week",
                List.of("searchEvents"), "conv-1");
    }

    @Test
    @DisplayName("blank first completion is retried once and a recovery is returned")
    void blankContentRetriesOnce() {
        when(callSpec.content()).thenReturn("   ", "Here are the matching events.");

        AssistantModelClient.ModelTurnResult result = client.execute(request());

        assertThat(result.assistantMessage()).isEqualTo("Here are the matching events.");
        verify(callSpec, times(2)).content();
        assertThat(registry.get(AiMetrics.PROVIDER_REQUESTS).tag("result", "invalid_response")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AiMetrics.PROVIDER_REQUESTS).tag("result", "success")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("repeated blank completions become AI_RESPONSE_INVALID after exactly two attempts")
    void repeatedBlankContentFailsInvalid() {
        when(callSpec.content()).thenReturn("", "  ");

        assertThatThrownBy(() -> client.execute(request()))
                .isInstanceOf(AssistantProviderException.class)
                .satisfies(ex -> assertThat(((AssistantProviderException) ex).getErrorCode())
                        .isEqualTo(AssistantChatErrorCode.AI_RESPONSE_INVALID));
        verify(callSpec, times(2)).content();
        assertThat(registry.get(AiMetrics.PROVIDER_REQUESTS).tag("result", "invalid_response")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("readable first completion performs a single provider call")
    void readableContentUsesSingleCall() {
        when(callSpec.content()).thenReturn("Hello.");

        AssistantModelClient.ModelTurnResult result = client.execute(request());

        assertThat(result.assistantMessage()).isEqualTo("Hello.");
        verify(callSpec, times(1)).content();
        assertThat(registry.get(AiMetrics.PROVIDER_REQUESTS).tag("result", "success")
                .counter().count()).isEqualTo(1.0);
    }
}
