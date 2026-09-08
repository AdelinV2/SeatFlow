package com.seatflow.analytics.support;

import com.seatflow.analytics.messaging.AnalyticsEventDispatcher;
import com.seatflow.analytics.messaging.ProjectionEventProcessor;
import com.seatflow.analytics.messaging.handlers.EventLifecycleProjectionHandler;
import com.seatflow.analytics.messaging.handlers.PaymentCompletedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.PaymentFailedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.PaymentRefundedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationCancelledProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationConfirmedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationExpiredProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationHeldProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationRefundedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.TicketIssuedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.TicketRevocationProjectionHandler;
import com.seatflow.analytics.messaging.handlers.TicketScanProjectionHandler;
import com.seatflow.analytics.projection.AnalyticsProjectionReconciler;
import com.seatflow.analytics.projection.EventSessionProjectionHandler;
import com.seatflow.analytics.projection.PaymentProjectionHandler;
import com.seatflow.analytics.projection.ReservationProjectionHandler;
import com.seatflow.analytics.projection.TicketProjectionHandler;
import com.seatflow.analytics.repository.AnalyticsPaymentFactRepository;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository;
import com.seatflow.analytics.repository.AnalyticsReservationFactRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketRevocationFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.DailyRevenueMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.repository.ProcessedEventRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Shared TASK-P14-007 projection wiring for {@code @DataJpaTest} integration tests.
 *
 * <p>Identical handler graph to production: full P14-002 claim path
 * ({@link ProjectionEventProcessor} + real adapters + {@link AnalyticsProjectionReconciler}).
 * Import with {@code @Import(AnalyticsProjectionTestConfig.class)} instead of duplicating
 * the adapter graph per test class.
 */
@TestConfiguration
public class AnalyticsProjectionTestConfig {

    @Bean
    ReservationProjectionHandler reservationProjectionHandler(
            AnalyticsReservationFactRepository reservations,
            AnalyticsPaymentFactRepository payments,
            AnalyticsTicketFactRepository tickets,
            AnalyticsSessionFactRepository sessions,
            EventSessionProjectionHandler sessionHandler) {
        return new ReservationProjectionHandler(reservations, payments, tickets, sessions, sessionHandler);
    }

    @Bean
    PaymentProjectionHandler paymentProjectionHandler(
            AnalyticsPaymentFactRepository payments,
            AnalyticsReservationFactRepository reservations,
            EventSessionProjectionHandler sessionHandler) {
        return new PaymentProjectionHandler(payments, reservations, sessionHandler);
    }

    @Bean
    TicketProjectionHandler ticketProjectionHandler(
            AnalyticsTicketFactRepository tickets,
            AnalyticsTicketRevocationFactRepository revocations,
            AnalyticsReservationFactRepository reservations,
            AnalyticsPaymentFactRepository payments,
            EventSessionProjectionHandler sessionHandler) {
        return new TicketProjectionHandler(tickets, revocations, reservations, payments, sessionHandler);
    }

    @Bean
    EventSessionProjectionHandler eventSessionProjectionHandler(
            AnalyticsSessionFactRepository sessions) {
        return new EventSessionProjectionHandler(sessions);
    }

    @Bean
    AnalyticsProjectionQueryRepository projectionQueryRepository() {
        return new AnalyticsProjectionQueryRepository();
    }

    @Bean
    AnalyticsProjectionReconciler reconciler(
            AnalyticsProjectionQueryRepository queries,
            AnalyticsSessionFactRepository sessions,
            EventSessionMetricRepository sessionMetrics,
            EventSessionRevenueMetricRepository sessionRevenue,
            DailyOperationalMetricRepository dailyOperational,
            DailyRevenueMetricRepository dailyRevenue) {
        return new AnalyticsProjectionReconciler(queries, sessions, sessionMetrics,
                sessionRevenue, dailyOperational, dailyRevenue);
    }

    @Bean
    ReservationHeldProjectionHandler heldAdapter(
            ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
        return new ReservationHeldProjectionHandler(reservations, reconciler);
    }

    @Bean
    ReservationConfirmedProjectionHandler confirmedAdapter(
            ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
        return new ReservationConfirmedProjectionHandler(reservations, reconciler);
    }

    @Bean
    ReservationExpiredProjectionHandler expiredAdapter(
            ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
        return new ReservationExpiredProjectionHandler(reservations, reconciler);
    }

    @Bean
    ReservationCancelledProjectionHandler cancelledAdapter(
            ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
        return new ReservationCancelledProjectionHandler(reservations, reconciler);
    }

    @Bean
    ReservationRefundedProjectionHandler reservationRefundedAdapter(
            ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
        return new ReservationRefundedProjectionHandler(reservations, reconciler);
    }

    @Bean
    PaymentCompletedProjectionHandler completedAdapter(
            PaymentProjectionHandler payments, AnalyticsProjectionReconciler reconciler) {
        return new PaymentCompletedProjectionHandler(payments, reconciler);
    }

    @Bean
    PaymentFailedProjectionHandler failedAdapter(
            PaymentProjectionHandler payments, AnalyticsProjectionReconciler reconciler) {
        return new PaymentFailedProjectionHandler(payments, reconciler);
    }

    @Bean
    PaymentRefundedProjectionHandler paymentRefundedAdapter(
            PaymentProjectionHandler payments, AnalyticsProjectionReconciler reconciler) {
        return new PaymentRefundedProjectionHandler(payments, reconciler);
    }

    @Bean
    TicketIssuedProjectionHandler issuedAdapter(
            TicketProjectionHandler tickets, AnalyticsProjectionReconciler reconciler) {
        return new TicketIssuedProjectionHandler(tickets, reconciler);
    }

    @Bean
    TicketRevocationProjectionHandler revocationAdapter(
            TicketProjectionHandler tickets, AnalyticsProjectionReconciler reconciler) {
        return new TicketRevocationProjectionHandler(tickets, reconciler);
    }

    @Bean
    TicketScanProjectionHandler scanAdapter(
            TicketProjectionHandler tickets, AnalyticsProjectionReconciler reconciler) {
        return new TicketScanProjectionHandler(tickets, reconciler);
    }

    @Bean
    EventLifecycleProjectionHandler lifecycleAdapter(
            EventSessionProjectionHandler sessions, AnalyticsProjectionReconciler reconciler) {
        return new EventLifecycleProjectionHandler(sessions, reconciler);
    }

    @Bean
    AnalyticsEventDispatcher dispatcher(
            ReservationHeldProjectionHandler held,
            ReservationConfirmedProjectionHandler confirmed,
            ReservationExpiredProjectionHandler expired,
            ReservationCancelledProjectionHandler cancelled,
            ReservationRefundedProjectionHandler reservationRefunded,
            PaymentCompletedProjectionHandler completed,
            PaymentFailedProjectionHandler failed,
            PaymentRefundedProjectionHandler paymentRefunded,
            TicketIssuedProjectionHandler issued,
            TicketRevocationProjectionHandler revocation,
            TicketScanProjectionHandler scan,
            EventLifecycleProjectionHandler lifecycle) {
        return new AnalyticsEventDispatcher(List.of(held, confirmed, expired, cancelled,
                reservationRefunded, completed, failed, paymentRefunded, issued, revocation,
                scan, lifecycle));
    }

    @Bean
    ProjectionEventProcessor processor(
            ProcessedEventRepository repository, AnalyticsEventDispatcher dispatcher) {
        return new ProjectionEventProcessor(repository, dispatcher);
    }
}
