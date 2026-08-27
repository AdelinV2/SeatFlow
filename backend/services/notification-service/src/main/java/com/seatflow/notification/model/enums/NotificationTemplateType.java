package com.seatflow.notification.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Supported transactional email template types")
public enum NotificationTemplateType {
    TICKET_ISSUED,
    PAYMENT_FAILED,
    RESERVATION_HELD
}
