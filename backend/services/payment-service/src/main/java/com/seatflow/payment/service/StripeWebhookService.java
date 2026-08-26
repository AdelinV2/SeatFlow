package com.seatflow.payment.service;

public interface StripeWebhookService {
    void handleWebhookEvent(String payload, String sigHeader);
}
