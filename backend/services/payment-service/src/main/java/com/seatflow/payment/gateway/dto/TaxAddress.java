package com.seatflow.payment.gateway.dto;

public record TaxAddress(
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country
) {
}

