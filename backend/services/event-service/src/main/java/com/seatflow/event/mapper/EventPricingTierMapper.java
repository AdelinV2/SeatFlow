package com.seatflow.event.mapper;

import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.web.dto.request.PricingTierItemRequest;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EventPricingTierMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "event", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    EventPricingTier toEntity(PricingTierItemRequest request);

    default EventPricingTier toEntity(PricingTierItemRequest request, Event event) {
        EventPricingTier tier = toEntity(request);
        tier.setEvent(event);
        return tier;
    }

    PricingTierResponse toResponse(EventPricingTier tier);
}
