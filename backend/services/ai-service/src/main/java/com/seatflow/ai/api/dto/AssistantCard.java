package com.seatflow.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative structured card constructed by the backend from validated tool results.
 *
 * <p>Only the fields relevant to {@link #type()} are populated; all others are {@code null}.
 * Cards are built from tool/application data wherever possible; model prose may explain cards but
 * cannot mutate card fields.
 *
 * <ul>
 *   <li>{@code EVENT}: eventId, title, category, venueSummary;</li>
 *   <li>{@code SESSION}: eventId, eventSessionId, startsAt/endsAt, status, bookable;</li>
 *   <li>{@code SEAT_SET}: eventSessionId, seatIds, seatLabels, sectionSummary, totalPriceMinor,
 *       currency, contiguous, reasons;</li>
 *   <li>{@code RESERVATION_PROPOSAL} draft: temporary proposalId, exact event/session, exact seat
 *       IDs/labels, exact total/currency snapshot, requiresExplicitConfirmation=true,
 *       seatsHeld=false. Visibly not a hold; finalized in P15-005;</li>
 *   <li>{@code INFO}: infoTitle/infoMessage for safe fallbacks and explanations.</li>
 * </ul>
 */
@Schema(description = "Authoritative structured card built from validated tool results")
public record AssistantCard(

        @Schema(description = "Card discriminator") AssistantCardType type,

        @Schema(description = "Event UUID for EVENT/SESSION/plaint") UUID eventId,
        @Schema(description = "Event title for EVENT") String title,
        @Schema(description = "Event category for EVENT") String category,
        @Schema(description = "Venue/public summary for EVENT") String venueSummary,

        @Schema(description = "Session UUID for SESSION/SEAT_SET/PROPOSAL") UUID eventSessionId,
        @Schema(description = "Session start (UTC) for SESSION") Instant startsAt,
        @Schema(description = "Session end (UTC) for SESSION") Instant endsAt,
        @Schema(description = "Authoritative session status for SESSION") String status,
        @Schema(description = "Bookability hint for SESSION: BOOKABLE, NOT_BOOKABLE, SALES_CLOSED")
        String bookable,

        @Schema(description = "Seat UUIDs for SEAT_SET/PROPOSAL") List<UUID> seatIds,
        @Schema(description = "Display labels for SEAT_SET/PROPOSAL") List<String> seatLabels,
        @Schema(description = "Section/row summary for SEAT_SET/PROPOSAL") String sectionSummary,
        @Schema(description = "Exact total in minor units for SEAT_SET/PROPOSAL") Long totalPriceMinor,
        @Schema(description = "ISO-4217 currency for SEAT_SET/PROPOSAL") String currency,
        @Schema(description = "True only for same-section, same-row, consecutive seat numbers")
        Boolean contiguous,
        @Schema(description = "Deterministic ranking reasons for SEAT_SET/PROPOSAL") List<String> reasons,

        @Schema(description = "Temporary orchestration/proposal draft ID for RESERVATION_PROPOSAL")
        UUID proposalId,
        @Schema(description = "Always true for RESERVATION_PROPOSAL drafts")
        Boolean requiresExplicitConfirmation,
        @Schema(description = "Always false in P15-004: a proposal is explicitly not a hold")
        Boolean seatsHeld,

        @Schema(description = "Title for INFO cards") String infoTitle,
        @Schema(description = "Message for INFO cards") String infoMessage
) {
    public AssistantCard {
        if (type == null) {
            throw new IllegalArgumentException("Assistant card type is required");
        }
        seatIds = seatIds == null ? null : List.copyOf(seatIds);
        seatLabels = seatLabels == null ? null : List.copyOf(seatLabels);
        reasons = reasons == null ? null : List.copyOf(reasons);
    }

    public static AssistantCard event(UUID eventId, String title, String category, String venueSummary) {
        return new AssistantCard(AssistantCardType.EVENT, eventId, title, category, venueSummary,
                null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    public static AssistantCard session(UUID eventId, UUID eventSessionId, Instant startsAt,
                                        Instant endsAt, String status, String bookable) {
        return new AssistantCard(AssistantCardType.SESSION, eventId, null, null, null,
                eventSessionId, startsAt, endsAt, status, bookable,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    public static AssistantCard seatSet(UUID eventSessionId, List<UUID> seatIds, List<String> seatLabels,
                                        String sectionSummary, long totalPriceMinor, String currency,
                                        boolean contiguous, List<String> reasons) {
        return new AssistantCard(AssistantCardType.SEAT_SET, null, null, null, null,
                eventSessionId, null, null, null, null,
                seatIds, seatLabels, sectionSummary, totalPriceMinor, currency, contiguous, reasons,
                null, null, null, null, null);
    }

    public static AssistantCard proposal(UUID eventId, UUID eventSessionId, UUID proposalId,
                                         List<UUID> seatIds, List<String> seatLabels,
                                         String sectionSummary, long totalPriceMinor, String currency,
                                         boolean contiguous, List<String> reasons) {
        return new AssistantCard(AssistantCardType.RESERVATION_PROPOSAL, eventId, null, null, null,
                eventSessionId, null, null, null, null,
                seatIds, seatLabels, sectionSummary, totalPriceMinor, currency, contiguous, reasons,
                proposalId, true, false, null, null);
    }

    public static AssistantCard info(String infoTitle, String infoMessage) {
        return new AssistantCard(AssistantCardType.INFO, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, infoTitle, infoMessage);
    }
}
