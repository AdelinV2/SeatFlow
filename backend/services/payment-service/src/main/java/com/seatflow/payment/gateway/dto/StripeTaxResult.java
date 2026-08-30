package com.seatflow.payment.gateway.dto;

import java.math.BigDecimal;

public record StripeTaxResult(
        BigDecimal taxAmount,
        BigDecimal effectiveRate,
        String currency
) {
}

