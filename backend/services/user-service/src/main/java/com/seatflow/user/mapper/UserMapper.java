package com.seatflow.user.mapper;

import com.seatflow.user.model.entity.User;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserMapper {

    UserProfileResponse toResponse(User user);
}
