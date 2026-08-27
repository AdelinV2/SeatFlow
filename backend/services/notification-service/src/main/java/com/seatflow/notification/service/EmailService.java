package com.seatflow.notification.service;

import com.seatflow.notification.web.dto.common.EmailAttachmentDto;

import java.util.List;

public interface EmailService {

    String sendEmail(String to, String subject, String htmlBody, List<EmailAttachmentDto> attachments);
}
