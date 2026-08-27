package com.seatflow.notification.service.impl;

import com.seatflow.notification.model.enums.NotificationTemplateType;
import com.seatflow.notification.service.EmailTemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThymeleafEmailTemplateRendererImpl implements EmailTemplateRenderer {

    private final ITemplateEngine templateEngine;

    @Override
    public String renderTemplate(NotificationTemplateType templateType, Map<String, Object> templateVariables) {
        log.debug("Rendering Thymeleaf email template for templateType={}", templateType);

        String templateName = resolveTemplateName(templateType);
        Context context = new Context(Locale.ENGLISH);
        if (templateVariables != null) {
            context.setVariables(templateVariables);
        }

        try {
            return templateEngine.process(templateName, context);
        } catch (Exception ex) {
            log.error("Failed to render Thymeleaf template '{}' for templateType={}", templateName, templateType, ex);
            throw new IllegalStateException("Failed to render email template: " + templateType, ex);
        }
    }

    private String resolveTemplateName(NotificationTemplateType templateType) {
        return switch (templateType) {
            case TICKET_ISSUED -> "ticket-issued";
            case PAYMENT_FAILED -> "payment-failed";
            case RESERVATION_HELD -> "reservation-held";
        };
    }
}
