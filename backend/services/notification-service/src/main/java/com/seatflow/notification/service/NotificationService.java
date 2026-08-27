package com.seatflow.notification.service;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.notification.messaging.event.PaymentFailedEvent;
import com.seatflow.notification.messaging.event.ReservationHeldEvent;
import com.seatflow.notification.messaging.event.TicketIssuedEvent;
import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.web.dto.response.NotificationLogResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationService {

    void sendTicketIssuedNotification(TicketIssuedEvent event);

    void sendPaymentFailedNotification(PaymentFailedEvent event);

    void sendReservationHeldNotification(ReservationHeldEvent event);

    NotificationLogResponse getNotificationById(UUID id);

    PagedResult<NotificationLogResponse> getNotifications(String recipientEmail, NotificationStatus status, Pageable pageable);

    void processFailedNotificationRetry(NotificationLog notificationLog);
}
