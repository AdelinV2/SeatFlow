package com.seatflow.event.model.common;

import java.math.BigDecimal;

public record EventPriceRange(BigDecimal minPrice, BigDecimal maxPrice, String currency) {}
