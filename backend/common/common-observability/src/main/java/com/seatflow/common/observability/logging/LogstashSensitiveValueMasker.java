package com.seatflow.common.observability.logging;

import com.fasterxml.jackson.core.JsonStreamContext;
import net.logstash.logback.mask.ValueMasker;

/**
 * Logstash JSON value masker for messages, MDC values, and serialized stack traces.
 */
public final class LogstashSensitiveValueMasker implements ValueMasker {

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (!(value instanceof CharSequence) && !(value instanceof Number)) {
            return null;
        }

        String originalValue = value.toString();
        String maskedValue = SensitiveDataMaskingConverter.mask(originalValue);
        return maskedValue.equals(originalValue) ? null : maskedValue;
    }
}
