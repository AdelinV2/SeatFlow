package com.seatflow.reservation.mapper;

import com.seatflow.reservation.web.dto.response.SeatHoldResponse;
import com.seatflow.reservation.model.entity.SeatHold;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SeatHoldMapper {

    @Mapping(target = "rowNumber", source = "rowLabel")
    SeatHoldResponse toResponse(SeatHold seatHold);

    java.util.List<SeatHoldResponse> toResponseList(java.util.List<SeatHold> seatHolds);
}
