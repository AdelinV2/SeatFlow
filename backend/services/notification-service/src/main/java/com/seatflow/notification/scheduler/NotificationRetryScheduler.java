package com.seatflow.notification.scheduler;

import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.repository.NotificationLogRepository;
import com.seatflow.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetryScheduler {

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationService notificationService;

    @Value("${seatflow.notification.max-retries:3}")
    private int maxRetries;

    @Value("${seatflow.notification.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${seatflow.notification.retry-interval-ms:30000}")
    public void retryFailedNotifications() {
        List<NotificationLog> failedLogs = notificationLogRepository.findFailedNotificationsForRetry(maxRetries, batchSize);

        if (failedLogs.isEmpty()) {
            log.trace("No failed notification records found for retry");
            return;
        }

        log.info("Found {} failed notification(s) eligible for retry", failedLogs.size());

        for (NotificationLog failedLog : failedLogs) {
            try {
                notificationService.processFailedNotificationRetry(failedLog);
            } catch (Exception ex) {
                log.error("Unexpected error in retry sweeper for notification id={}: {}",
                        failedLog.getId(), ex.getMessage(), ex);
            }
        }
    }
}
