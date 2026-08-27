package com.seatflow.ticket.client.exception;

public class InterServiceClientException extends RuntimeException {

    public InterServiceClientException(String message) {
        super(message);
    }

    public InterServiceClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
