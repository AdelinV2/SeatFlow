package com.seatflow.seatmap.mapper;

import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SeatMapper {

    @Mapping(source = "id", target = "seatId")
    SeatResponse toResponse(Seat seat);
}
