package com.seatflow.seatmap.mapper;

import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.web.dto.response.VenueDetailResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VenueMapper {

    VenueResponse toResponse(Venue venue);

    @Mapping(target = "id", source = "venue.id")
    @Mapping(target = "name", source = "venue.name")
    @Mapping(target = "address", source = "venue.address")
    @Mapping(target = "city", source = "venue.city")
    @Mapping(target = "country", source = "venue.country")
    @Mapping(target = "capacity", source = "venue.capacity")
    @Mapping(target = "sections", source = "sections")
    @Mapping(target = "createdAt", source = "venue.createdAt")
    VenueDetailResponse toDetailResponse(Venue venue, List<VenueSectionResponse> sections);
}
