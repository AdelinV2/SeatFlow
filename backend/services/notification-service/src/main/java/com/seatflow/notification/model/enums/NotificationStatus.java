package com.seatflow.notification.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Lifecycle status of a notification dispatch attempt")
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
