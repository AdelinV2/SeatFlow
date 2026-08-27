package com.seatflow.notification.client.resend.exception;

public class ResendClientException extends RuntimeException {

    public ResendClientException(String message) {
        super(message);
    }

    public ResendClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
