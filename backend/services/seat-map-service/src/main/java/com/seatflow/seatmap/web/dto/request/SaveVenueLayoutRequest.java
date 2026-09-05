package com.seatflow.seatmap.web.dto.request;

import com.seatflow.seatmap.model.enums.LayoutElementType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "Complete typed editor snapshot for atomic venue layout save")
public record SaveVenueLayoutRequest(
    @Schema(description = "Expected layout version for optimistic concurrency", example = "7")
    @NotNull(message = "Layout version is required")
    @PositiveOrZero(message = "Layout version must be zero or positive")
    Long layoutVersion,

    @Schema(description = "Section upserts in this snapshot (may be empty)")
    @NotNull(message = "Sections list is required")
    List<@Valid SectionUpsert> sections,

    @Schema(description = "Non-bookable layout element upserts in this snapshot (may be empty)")
    @NotNull(message = "Elements list is required")
    List<@Valid LayoutElementUpsert> elements
) {
    @Schema(description = "Section create-or-update entry within a layout snapshot")
    public record SectionUpsert(
        @Schema(description = "Existing section ID; null for new sections")
        UUID sectionId,

        @Schema(description = "Section name", example = "Orchestra")
        @NotBlank(message = "Section name is required")
        @Size(max = 100, message = "Section name must not exceed 100 characters")
        String name,

        @Schema(description = "Number of rows", example = "10")
        @NotNull(message = "Row count is required")
        @Min(value = 1, message = "Row count must be at least 1")
        Integer rowCount,

        @Schema(description = "Number of columns", example = "20")
        @NotNull(message = "Column count is required")
        @Min(value = 1, message = "Column count must be at least 1")
        Integer colCount,

        @Schema(description = "Whether the section is active")
        @NotNull(message = "Section active flag is required")
        Boolean isActive,

        @Schema(description = "Section position X on venue canvas (0..100000)")
        @NotNull(message = "Section positionX is required")
        BigDecimal positionX,

        @Schema(description = "Section position Y on venue canvas (0..100000)")
        @NotNull(message = "Section positionY is required")
        BigDecimal positionY,

        @Schema(description = "Section width (>0..100000)")
        @NotNull(message = "Section width is required")
        BigDecimal width,

        @Schema(description = "Section height (>0..100000)")
        @NotNull(message = "Section height is required")
        BigDecimal height,

        @Schema(description = "Section rotation in degrees (-180..180)")
        @NotNull(message = "Section rotation is required")
        BigDecimal rotationDeg,

        @Schema(description = "Section z-index (-1000..1000)")
        @NotNull(message = "Section z-index is required")
        Integer zIndex,

        @Schema(description = "Optional shape metadata; must be a JSON object when present")
        Object shapeMetadata,

        @Schema(description = "Seat upserts in this section")
        @NotNull(message = "Seats list is required")
        List<@Valid SeatUpsert> seats
    ) {
        /**
         * Authoritative canonicalization for TASK-P11-003 §5.3 rules 1-2 and 5.
         * Trims the persisted business identifier so every construction path
         * (Jackson deserialization, manual {@code new}, future TASK-P11-004 save flow)
         * receives the canonical value. Null is preserved; blank-after-trim
         * rejection stays in {@code LayoutValidationService} on the
         * {@code ValidationException}/{@code INVALID_REQUEST} path.
         */
        public SectionUpsert {
            if (name != null) {
                name = name.trim();
            }
        }
    }

    @Schema(description = "Seat create-or-update entry within a section")
    public record SeatUpsert(
        @Schema(description = "Existing seat ID; null for new seats")
        UUID seatId,

        @Schema(description = "Row label", example = "A")
        @NotBlank(message = "Row label is required")
        @Size(max = 10, message = "Row label must not exceed 10 characters")
        String rowLabel,

        @Schema(description = "Seat number within the row", example = "1")
        @NotNull(message = "Seat number is required")
        @Positive(message = "Seat number must be positive")
        Integer seatNumber,

        @Schema(description = "Grid X coordinate (0-based column index)")
        @NotNull(message = "Grid X is required")
        @PositiveOrZero(message = "Grid X must be zero or positive")
        Integer gridX,

        @Schema(description = "Grid Y coordinate (0-based row index)")
        @NotNull(message = "Grid Y is required")
        @PositiveOrZero(message = "Grid Y must be zero or positive")
        Integer gridY,

        @Schema(description = "Seat position X local to its section")
        @NotNull(message = "Seat positionX is required")
        BigDecimal positionX,

        @Schema(description = "Seat position Y local to its section")
        @NotNull(message = "Seat positionY is required")
        BigDecimal positionY,

        @Schema(description = "Whether the seat is active/bookable")
        @NotNull(message = "Seat active flag is required")
        Boolean isActive
    ) {
        /**
         * Authoritative canonicalization for TASK-P11-003 §5.3 rules 1 and 5.
         * Trims the persisted business identifier so every construction path
         * receives the canonical value. Null is preserved; blank-after-trim
         * rejection stays in {@code LayoutValidationService} on the
         * {@code ValidationException}/{@code INVALID_REQUEST} path.
         */
        public SeatUpsert {
            if (rowLabel != null) {
                rowLabel = rowLabel.trim();
            }
        }
    }

    @Schema(description = "Non-bookable layout element create-or-update entry")
    public record LayoutElementUpsert(
        @Schema(description = "Existing element ID; null for new elements")
        UUID elementId,

        @Schema(description = "Element type", example = "STAGE")
        @NotNull(message = "Element type is required")
        LayoutElementType type,

        @Schema(description = "Optional display label; required for LABEL type", example = "Main Stage")
        @Size(max = 255, message = "Element label must not exceed 255 characters")
        String label,

        @Schema(description = "Typed element geometry")
        @NotNull(message = "Element geometry is required")
        @Valid Geometry geometry,

        @Schema(description = "Element z-index (-1000..1000)")
        @NotNull(message = "Element z-index is required")
        Integer zIndex
    ) {
        /**
         * Authoritative canonicalization for TASK-P11-003 §5.3 rule 12.
         * Trims the persisted display label so every construction path
         * (Jackson deserialization, manual {@code new}, future TASK-P11-004 save flow)
         * receives the canonical value. Null is preserved for non-LABEL types;
         * blank-after-trim rejection for LABEL stays in
         * {@code LayoutValidationService} on the
         * {@code ValidationException}/{@code INVALID_REQUEST} path.
         */
        public LayoutElementUpsert {
            if (label != null) {
                label = label.trim();
            }
        }
    }

    @Schema(description = "Typed rectangular geometry for layout elements")
    public record Geometry(
        @Schema(description = "Geometry X on venue canvas (0..100000)")
        @NotNull(message = "Geometry x is required")
        BigDecimal x,

        @Schema(description = "Geometry Y on venue canvas (0..100000)")
        @NotNull(message = "Geometry y is required")
        BigDecimal y,

        @Schema(description = "Geometry width (>0..100000)")
        @NotNull(message = "Geometry width is required")
        BigDecimal width,

        @Schema(description = "Geometry height (>0..100000)")
        @NotNull(message = "Geometry height is required")
        BigDecimal height,

        @Schema(description = "Geometry rotation in degrees (-180..180)")
        @NotNull(message = "Geometry rotation is required")
        BigDecimal rotationDeg
    ) {}
}
