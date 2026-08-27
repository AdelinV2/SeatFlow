package com.seatflow.ticket.client.dto;

import java.time.Instant;
import java.util.UUID;

public record EventClientResponse(
    UUID id,
    UUID venueId,
    String title,
    String category,
    Instant eventDate,
    String status,
    String bannerUrl
) {}
