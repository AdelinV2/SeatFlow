package com.seatflow.ai.service.seat;

import java.util.Optional;
import java.util.UUID;

/**
 * One priced, available seat inside a session inventory snapshot.
 *
 * <p>Instances are created only for seats that passed the composition filters: present in the
 * seat-map read model, active, reported AVAILABLE by the Reservation Service, and carrying a
 * resolved single-currency price. Global coordinates are absent when geometry cannot be derived
 * reliably; ranking then falls back to neutral criteria for the affected candidates.
 */
public record PricedSeat(
        UUID seatId,
        UUID sectionId,
        String sectionName,
        String rowLabel,
        int seatNumber,
        Optional<SeatGeometry.Point> globalPoint,
        String categoryName,
        UUID pricingTierId,
        long priceMinor,
        String currency
) {}
