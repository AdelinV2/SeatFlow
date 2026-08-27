package com.seatflow.notification.scheduler;

import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.model.enums.NotificationTemplateType;
import com.seatflow.notification.repository.NotificationLogRepository;
import com.seatflow.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationRetrySchedulerTest {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationRetryScheduler retryScheduler;

    @Test
    @DisplayName("Should sweep and retry failed notifications")
    void shouldSweepAndRetryFailedNotifications() {
        ReflectionTestUtils.setField(retryScheduler, "maxRetries", 3);
        ReflectionTestUtils.setField(retryScheduler, "batchSize", 50);

        NotificationLog failed1 = NotificationLog.builder()
                .id(UUID.randomUUID())
                .recipientEmail("fail1@example.com")
                .templateType(NotificationTemplateType.TICKET_ISSUED)
                .status(NotificationStatus.FAILED)
                .retryCount(0)
                .build();

        NotificationLog failed2 = NotificationLog.builder()
                .id(UUID.randomUUID())
                .recipientEmail("fail2@example.com")
                .templateType(NotificationTemplateType.PAYMENT_FAILED)
                .status(NotificationStatus.FAILED)
                .retryCount(1)
                .build();

        when(notificationLogRepository.findFailedNotificationsForRetry(3, 50))
                .thenReturn(List.of(failed1, failed2));

        retryScheduler.retryFailedNotifications();

        verify(notificationLogRepository).findFailedNotificationsForRetry(3, 50);
        verify(notificationService).processFailedNotificationRetry(failed1);
        verify(notificationService).processFailedNotificationRetry(failed2);
    }

    @Test
    @DisplayName("Should do nothing when no failed notifications exist")
    void shouldDoNothingWhenNoFailedNotifications() {
        ReflectionTestUtils.setField(retryScheduler, "maxRetries", 3);
        ReflectionTestUtils.setField(retryScheduler, "batchSize", 50);

        when(notificationLogRepository.findFailedNotificationsForRetry(anyInt(), anyInt()))
                .thenReturn(List.of());

        retryScheduler.retryFailedNotifications();

        verify(notificationLogRepository).findFailedNotificationsForRetry(3, 50);
        verifyNoInteractions(notificationService);
    }
}
