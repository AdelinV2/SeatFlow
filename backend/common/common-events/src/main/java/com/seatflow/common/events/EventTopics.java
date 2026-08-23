package com.seatflow.common.events;

public final class EventTopics {
    public static final String RESERVATION_EVENTS = "seatflow.reservation.events";
    public static final String PAYMENT_EVENTS = "seatflow.payment.events";
    public static final String TICKET_EVENTS = "seatflow.ticket.events";
    public static final String NOTIFICATION_EVENTS = "seatflow.notification.events";

    private EventTopics() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
