package com.seatflow.reservation.mapper;

import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatHoldResponse;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReservationMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "seatHolds", ignore = true)
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "expiresAt", ignore = true)
    @Mapping(target = "totalAmount", ignore = true)
    @Mapping(target = "seatCount", expression = "java(request.seatIds().size())")
    Reservation toEntity(CreateReservationRequest request, UUID userId);

    default ReservationResponse toResponse(Reservation reservation) {
        if (reservation == null) {
            return null;
        }
        List<SeatHoldResponse> seats = (reservation.getSeatHolds() == null)
                ? List.of()
                : reservation.getSeatHolds().stream()
                    .map(this::toSeatHoldResponse)
                    .toList();
        return new ReservationResponse(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getUserId(),
                reservation.getCustomerEmail(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                reservation.getTotalAmount(),
                reservation.getSeatCount(),
                seats,
                reservation.getCreatedAt()
        );
    }

    default SeatHoldResponse toSeatHoldResponse(SeatHold seatHold) {
        if (seatHold == null) {
            return null;
        }
        return new SeatHoldResponse(
                seatHold.getId(),
                seatHold.getSeatId(),
                seatHold.getStatus(),
                seatHold.getPrice(),
                seatHold.getRowLabel(),
                seatHold.getSeatNumber(),
                seatHold.getPricingTierId(),
                seatHold.getTicketType()
        );
    }

    List<ReservationResponse> toResponseList(List<Reservation> reservations);
}
