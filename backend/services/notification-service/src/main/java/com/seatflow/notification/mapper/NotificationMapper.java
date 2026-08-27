package com.seatflow.notification.mapper;

import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.web.dto.response.NotificationLogResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface NotificationMapper {

    NotificationLogResponse toResponse(NotificationLog notificationLog);

    List<NotificationLogResponse> toResponseList(List<NotificationLog> notificationLogs);
}
