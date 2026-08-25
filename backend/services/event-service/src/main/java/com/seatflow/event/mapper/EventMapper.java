package com.seatflow.event.mapper;

import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        uses = {EventPricingTierMapper.class},
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EventMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "DRAFT")
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "pricingTiers", ignore = true)
    Event toEntity(CreateEventRequest request);

    EventDetailResponse toDetailResponse(Event event);

    @Mapping(target = "id", source = "event.id")
    @Mapping(target = "title", source = "event.title")
    @Mapping(target = "category", source = "event.category")
    @Mapping(target = "bannerUrl", source = "event.bannerUrl")
    @Mapping(target = "eventDate", source = "event.eventDate")
    @Mapping(target = "minPrice", source = "minPrice")
    @Mapping(target = "maxPrice", source = "maxPrice")
    @Mapping(target = "currency", source = "currency")
    EventSummaryResponse toSummaryResponse(Event event, BigDecimal minPrice, BigDecimal maxPrice, String currency);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "venueId", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "pricingTiers", ignore = true)
    void updateEntity(UpdateEventRequest request, @MappingTarget Event event);
}
