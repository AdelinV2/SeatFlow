package com.seatflow.payment.service;

import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.request.TaxPreviewRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.TaxPreviewResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;

import java.util.UUID;

public interface PaymentService {

    PaymentIntentResponse createPaymentIntent(CreatePaymentIntentRequest request, UUID authenticatedUserId);

    TaxPreviewResponse calculateTaxPreview(UUID paymentId,
                                           TaxPreviewRequest request,
                                           UUID authenticatedUserId,
                                           boolean isAdmin);

    PaymentResponse getPaymentById(UUID paymentId, UUID authenticatedUserId, boolean isAdmin);

    PaymentResponse getPaymentByReservationId(UUID reservationId, UUID authenticatedUserId, boolean isAdmin);

    int claimGuestPayments(UUID userId, String customerEmail);
}
