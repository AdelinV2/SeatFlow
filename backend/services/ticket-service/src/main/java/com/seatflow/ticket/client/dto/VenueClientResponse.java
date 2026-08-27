package com.seatflow.ticket.client.dto;

import java.util.List;
import java.util.UUID;

public record VenueClientResponse(
    UUID id,
    String name,
    String address,
    String city,
    String country,
    Integer capacity
) {}
