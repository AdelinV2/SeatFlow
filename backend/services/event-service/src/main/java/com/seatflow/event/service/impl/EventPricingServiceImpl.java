package com.seatflow.event.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.event.client.SeatMapClient;
import com.seatflow.event.client.VenueSeatMapResponse;
import com.seatflow.event.client.VenueSectionResponse;
import com.seatflow.event.mapper.EventPricingTierMapper;
import com.seatflow.event.model.common.EventPriceRange;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventPricingTier;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventPricingTierRepository;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.projection.PriceRangeProjection;
import com.seatflow.event.service.EventPricingService;
import com.seatflow.event.web.dto.request.ConfigurePricingRequest;
import com.seatflow.event.web.dto.request.PricingTierItemRequest;
import com.seatflow.event.web.dto.response.PricingTierResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventPricingServiceImpl implements EventPricingService {

    private final EventRepository eventRepository;
    private final EventPricingTierRepository pricingTierRepository;
    private final EventPricingTierMapper tierMapper;
    private final SeatMapClient seatMapClient;

    @Override
    @Transactional
    public List<PricingTierResponse> configurePricing(UUID eventId, ConfigurePricingRequest request) {
        Event event = eventRepository.findWithPricingTiersById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event: ", eventId));
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.COMPLETED) {
            throw new ValidationException("Pricing cannot be modified for a terminal event", ErrorCode.INVALID_REQUEST);
        }
        List<PricingTierItemRequest> items = request.pricingTiers();

        Set<String> seen = new HashSet<>();
        for (PricingTierItemRequest item : items) {
            String key = item.sectionId() + "|" + item.categoryName();
            if (!seen.add(key)) {
                throw new ValidationException("Duplicate (sectionId, categoryName) pricing tier", ErrorCode.INVALID_REQUEST);
            }
        }

        Set<String> currencies = items.stream().map(PricingTierItemRequest::currency).collect(Collectors.toSet());
        if (currencies.size() > 1) {
            throw new ValidationException("All pricing tiers must use a single currency", ErrorCode.INVALID_REQUEST);
        }

        Set<UUID> validSectionIds = loadVenueSectionIds(event.getVenueId());
        for (PricingTierItemRequest item : items) {
            if (!validSectionIds.contains(item.sectionId())) {
                throw new ValidationException("Section does not belong to the event venue", ErrorCode.INVALID_REQUEST);
            }
        }

        event.getPricingTiers().clear();
        List<EventPricingTier> tiers = items.stream()
                .map(item -> tierMapper.toEntity(item, event)).toList();
        event.getPricingTiers().addAll(tiers);
        event.setUpdatedAt(Instant.now());
        Event saved = eventRepository.save(event);
        pricingTierRepository.flush();

        return pricingTierRepository.findByEvent_IdOrderByPriceAsc(saved.getId()).stream()
                .map(tierMapper::toResponse)
                .sorted(Comparator.comparing(PricingTierResponse::price))
                .toList();
    }

    private Set<UUID> loadVenueSectionIds(UUID venueId) {
        try {
            VenueSeatMapResponse venue = seatMapClient.getVenueSeatMap(venueId);
            if (venue == null || venue.sections() == null) {
                return Set.of();
            }
            return venue.sections().stream()
                    .map(VenueSectionResponse::sectionId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            throw new BusinessException("Venue validation service unavailable", ErrorCode.INTERNAL_SERVER_ERROR, 500);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PricingTierResponse> getPricingTiers(UUID eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event", eventId);
        }
        return pricingTierRepository.findByEvent_IdOrderByPriceAsc(eventId).stream()
                .map(tierMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EventPriceRange getPriceRange(UUID eventId) {
        PriceRangeProjection projection = pricingTierRepository.findPriceRangeByEventId(eventId);
        if (projection == null || projection.getMinPrice() == null) {
            return new EventPriceRange(null, null, null);
        }
        return new EventPriceRange(projection.getMinPrice(), projection.getMaxPrice(), projection.getCurrency());
    }
}
