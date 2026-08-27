package com.seatflow.ticket.mapper;

import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import com.seatflow.ticket.web.dto.response.TicketSummaryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface TicketMapper {

    TicketResponse toResponse(Ticket ticket);

    TicketDetailResponse toDetailResponse(Ticket ticket);

    TicketSummaryResponse toSummaryResponse(Ticket ticket);

    List<TicketResponse> toResponseList(List<Ticket> tickets);
}
