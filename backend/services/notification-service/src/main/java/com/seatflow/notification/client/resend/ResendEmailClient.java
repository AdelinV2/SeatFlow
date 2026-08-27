package com.seatflow.notification.client.resend;

import com.seatflow.notification.client.resend.dto.ResendEmailRequest;
import com.seatflow.notification.client.resend.dto.ResendEmailResponse;

public interface ResendEmailClient {

    ResendEmailResponse sendEmail(ResendEmailRequest request);
}
