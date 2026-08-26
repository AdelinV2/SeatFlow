package com.seatflow.payment.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.payment.service.PaymentService;
import com.seatflow.payment.service.StripeWebhookService;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Stripe Payment Intent and Webhook processing APIs")
public class PaymentController {

    private final PaymentService paymentService;
    private final StripeWebhookService stripeWebhookService;

    @PostMapping("/intent")
    @Operation(
        summary = "Create Stripe PaymentIntent for a pending reservation",
        description = "Initializes a payment intent in Stripe for an active, non-expired seat reservation. Supports both authenticated customers and guests (ADR-001)."
    )
    @ApiResponse(responseCode = "201", description = "PaymentIntent created successfully",
        content = @Content(schema = @Schema(implementation = PaymentIntentResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error, reservation expired, or invalid status",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Reservation not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Reservation is already paid or idempotency key conflict",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "Stripe gateway failure",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PaymentIntentResponse> createPaymentIntent(
            @Valid @RequestBody CreatePaymentIntentRequest request) {

        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);

        PaymentIntentResponse response = paymentService.createPaymentIntent(request, authenticatedUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{paymentId}")
    @Operation(
        summary = "Get payment details by payment ID",
        description = "Retrieves payment status, transaction amounts, and Stripe identifiers."
    )
    @ApiResponse(responseCode = "200", description = "Payment found",
        content = @Content(schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "404", description = "Payment not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID paymentId) {
        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        PaymentResponse response = paymentService.getPaymentById(paymentId, authenticatedUserId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reservation/{reservationId}")
    @Operation(
        summary = "Get payment details by reservation ID",
        description = "Retrieves payment record associated with a specific reservation."
    )
    @ApiResponse(responseCode = "200", description = "Payment found",
        content = @Content(schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "404", description = "Payment for reservation not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PaymentResponse> getPaymentByReservation(@PathVariable UUID reservationId) {
        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        PaymentResponse response = paymentService.getPaymentByReservationId(reservationId, authenticatedUserId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/webhook")
    @Operation(
        summary = "Stripe webhook event handler",
        description = "Receives signed asynchronous event notifications from Stripe (payment_intent.succeeded, payment_intent.payment_failed)."
    )
    @ApiResponse(responseCode = "200", description = "Webhook event received and processed")
    @ApiResponse(responseCode = "400", description = "Invalid Stripe signature or malformed payload",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Map<String, Boolean>> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        if (sigHeader == null || sigHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("received", false));
        }

        stripeWebhookService.handleWebhookEvent(payload, sigHeader);
        return ResponseEntity.ok(Map.of("received", true));
    }
}
