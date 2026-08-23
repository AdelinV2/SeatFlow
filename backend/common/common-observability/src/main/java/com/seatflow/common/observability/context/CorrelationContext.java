package com.seatflow.common.observability.context;

import java.util.Optional;

public final class CorrelationContext {
    private static final ThreadLocal<String> CURRENT_CORRELATION_ID = new ThreadLocal<>();

    private CorrelationContext() {}

    public static void setCorrelationId(String correlationId) {
        CURRENT_CORRELATION_ID.set(correlationId);
    }

    public static Optional<String> getCorrelationId() {
        return Optional.ofNullable(CURRENT_CORRELATION_ID.get());
    }

    public static void clear() {
        CURRENT_CORRELATION_ID.remove();
    }
}
