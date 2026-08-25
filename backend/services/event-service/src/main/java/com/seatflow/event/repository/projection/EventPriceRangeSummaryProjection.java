package com.seatflow.event.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

public interface EventPriceRangeSummaryProjection {
    UUID getEventId();
    BigDecimal getMinPrice();
    BigDecimal getMaxPrice();
    String getCurrency();
}
