package com.seatflow.notification.service;

import com.seatflow.notification.model.enums.NotificationTemplateType;

import java.util.Map;

public interface EmailTemplateRenderer {

    String renderTemplate(NotificationTemplateType templateType, Map<String, Object> templateVariables);
}
