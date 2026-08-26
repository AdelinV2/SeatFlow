package com.seatflow.reservation.mapper;

import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatHoldResponse;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = SeatHoldMapper.class)
public interface ReservationMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "seatHolds", ignore = true)
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "expiresAt", expression = "java(java.time.Instant.now().plusSeconds(900))")
    @Mapping(target = "totalAmount", expression = "java(totalFromRequest(request))")
    @Mapping(target = "seatCount", expression = "java(request.seatIds().size())")
    @Mapping(target = "userId", source = "userId")
    Reservation toEntity(CreateReservationRequest request, UUID userId);

    @Mapping(target = "seats", source = "seatHolds")
    ReservationResponse toResponse(Reservation reservation);

    @Mapping(target = "seats", source = "seatHolds")
    List<ReservationResponse> toResponseList(List<Reservation> reservations);

    default BigDecimal totalFromRequest(CreateReservationRequest request) {
        if (request == null || request.seatPrices() == null) {
            return BigDecimal.ZERO;
        }
        return request.seatPrices().stream()
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
