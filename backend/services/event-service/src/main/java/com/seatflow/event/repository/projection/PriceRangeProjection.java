package com.seatflow.event.repository.projection;

import java.math.BigDecimal;

public interface PriceRangeProjection {
    BigDecimal getMinPrice();
    BigDecimal getMaxPrice();
    String getCurrency();
}
