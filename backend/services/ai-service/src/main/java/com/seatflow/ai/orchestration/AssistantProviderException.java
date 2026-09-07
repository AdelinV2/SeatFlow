package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import lombok.Getter;

/**
 * Provider/model failure carrying a stable TASK-P15-004 section 10 code.
 *
 * <p>Never carries raw provider bodies, stack-trace details for clients, keys, tokens, or
 * chain-of-thought. The orchestrator converts this to an {@code ERROR_RECOVERABLE} response with
 * the stable code.
 */
@Getter
public class AssistantProviderException extends RuntimeException {

    private final AssistantChatErrorCode errorCode;

    public AssistantProviderException(AssistantChatErrorCode errorCode, String safeMessage) {
        super(safeMessage);
        this.errorCode = errorCode;
    }

    public AssistantProviderException(AssistantChatErrorCode errorCode, String safeMessage,
                                      Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = errorCode;
    }
}
