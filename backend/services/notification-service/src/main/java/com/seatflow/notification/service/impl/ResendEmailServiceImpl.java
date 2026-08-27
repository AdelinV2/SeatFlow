package com.seatflow.notification.service.impl;

import com.seatflow.notification.client.resend.ResendEmailClient;
import com.seatflow.notification.client.resend.dto.ResendAttachment;
import com.seatflow.notification.client.resend.dto.ResendEmailRequest;
import com.seatflow.notification.client.resend.dto.ResendEmailResponse;
import com.seatflow.notification.config.ResendProperties;
import com.seatflow.notification.service.EmailService;
import com.seatflow.notification.web.dto.common.EmailAttachmentDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResendEmailServiceImpl implements EmailService {

    private final ResendEmailClient resendEmailClient;
    private final ResendProperties resendProperties;

    @Override
    public String sendEmail(String to, String subject, String htmlBody, List<EmailAttachmentDto> attachments) {
        log.info("Preparing email transmission via Resend to={}, subject={}, attachmentCount={}",
                to, subject, attachments != null ? attachments.size() : 0);

        List<ResendAttachment> resendAttachments = new ArrayList<>();
        if (attachments != null) {
            for (EmailAttachmentDto attachment : attachments) {
                if (attachment.content() != null && attachment.content().length > 0) {
                    String base64Content = Base64.getEncoder().encodeToString(attachment.content());
                    resendAttachments.add(new ResendAttachment(attachment.filename(), base64Content));
                }
            }
        }

        ResendEmailRequest request = new ResendEmailRequest(
                resendProperties.getFromEmail(),
                List.of(to),
                subject,
                htmlBody,
                resendAttachments
        );

        ResendEmailResponse response = resendEmailClient.sendEmail(request);
        return response.id();
    }
}
