package com.seatflow.event.service;

import com.seatflow.event.model.common.EventPriceRange;
import com.seatflow.event.web.dto.request.ConfigurePricingRequest;
import com.seatflow.event.web.dto.response.PricingTierResponse;

import java.util.List;
import java.util.UUID;

public interface EventPricingService {

    List<PricingTierResponse> configurePricing(UUID eventId, ConfigurePricingRequest request);

    List<PricingTierResponse> getPricingTiers(UUID eventId);

    EventPriceRange getPriceRange(UUID eventId);
}
