package com.seatflow.seatmap.mapper;

import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VenueSectionMapper {

    @Mapping(target = "id", source = "section.id")
    @Mapping(target = "name", source = "section.name")
    @Mapping(target = "rowCount", source = "section.rowCount")
    @Mapping(target = "colCount", source = "section.colCount")
    @Mapping(target = "activeSeatCount", source = "activeSeatCount")
    VenueSectionResponse toResponse(VenueSection section, Long activeSeatCount);
}
