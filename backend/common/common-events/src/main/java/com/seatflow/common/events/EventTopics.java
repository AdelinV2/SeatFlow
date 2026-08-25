package com.seatflow.common.events;

public final class EventTopics {
    public static final String USER_EVENTS = "seatflow.user.events";
    public static final String RESERVATION_EVENTS = "seatflow.reservation.events";
    public static final String PAYMENT_EVENTS = "seatflow.payment.events";
    public static final String TICKET_EVENTS = "seatflow.ticket.events";
    public static final String NOTIFICATION_EVENTS = "seatflow.notification.events";
    public static final String SEATMAP_EVENTS = "seatflow.seatmap.events";
    public static final String EVENT_EVENTS = "seatflow.event.events";

    private EventTopics() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
