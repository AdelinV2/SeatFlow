package com.seatflow.notification.model.common;

import com.seatflow.notification.model.enums.NotificationTemplateType;
import com.seatflow.notification.web.dto.common.EmailAttachmentDto;

import java.util.List;
import java.util.Map;

public record SendEmailCommand(
        String recipientEmail,
        String subject,
        NotificationTemplateType templateType,
        String idempotencyKey,
        Map<String, Object> templateVariables,
        List<EmailAttachmentDto> attachments
) {
    public SendEmailCommand {
        if (attachments == null) {
            attachments = List.of();
        }
        if (templateVariables == null) {
            templateVariables = Map.of();
        }
    }
}
