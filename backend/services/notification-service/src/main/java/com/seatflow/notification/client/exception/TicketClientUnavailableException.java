package com.seatflow.notification.client.exception;

public class TicketClientUnavailableException extends RuntimeException {

    public TicketClientUnavailableException(String message) {
        super(message);
    }

    public TicketClientUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
