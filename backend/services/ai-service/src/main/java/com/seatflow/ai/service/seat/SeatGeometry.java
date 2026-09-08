package com.seatflow.ai.service.seat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Venue-global geometry for deterministic seat ranking (TASK-P15-003 section 7).
 *
 * <p>Seat positions are section-relative; global coordinates add the owning section offset:
 * {@code global = seatLocal + sectionOffset}. Rotation is deliberately <em>not</em> applied:
 * when any participating section carries a non-zero {@code rotationDeg}, global geometry cannot
 * be derived reliably, so {@link #isReliable(boolean)} reports unreliable and geometry-dependent
 * strategies fall back to documented neutral criteria instead of inventing a stage distance.
 *
 * <p>All comparisons use exact {@link BigDecimal} squared distances — no floating-point
 * arithmetic, no opaque weights.
 */
public final class SeatGeometry {

    private static final int CENTER_SCALE = 10;

    private SeatGeometry() {
    }

    /** Venue-global point derived from section-relative seat coordinates. */
    public record Point(BigDecimal x, BigDecimal y) {
        public BigDecimal squaredDistanceTo(Point other) {
            BigDecimal dx = x.subtract(other.x);
            BigDecimal dy = y.subtract(other.y);
            return dx.multiply(dx).add(dy.multiply(dy));
        }
    }

    public static Optional<Point> globalPoint(
            BigDecimal seatX, BigDecimal seatY, BigDecimal sectionX, BigDecimal sectionY) {
        if (seatX == null || seatY == null) {
            return Optional.empty();
        }
        BigDecimal baseX = sectionX == null ? BigDecimal.ZERO : sectionX;
        BigDecimal baseY = sectionY == null ? BigDecimal.ZERO : sectionY;
        return Optional.of(new Point(seatX.add(baseX), seatY.add(baseY)));
    }

    public static boolean hasRotation(BigDecimal rotationDeg) {
        return rotationDeg != null && rotationDeg.compareTo(BigDecimal.ZERO) != 0;
    }

    /** Stage center from rectangular geometry ({@code x + w/2, y + h/2}); empty when unusable. */
    public static Optional<Point> stageCenter(
            BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
        if (x == null || y == null || width == null || height == null) {
            return Optional.empty();
        }
        if (width.signum() <= 0 || height.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal two = BigDecimal.valueOf(2);
        return Optional.of(new Point(
                x.add(width.divide(two, CENTER_SCALE, RoundingMode.HALF_UP)),
                y.add(height.divide(two, CENTER_SCALE, RoundingMode.HALF_UP))));
    }

    /** Bounding-box center over global seat coordinates; empty when no point is available. */
    public static Optional<Point> venueCenter(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal minX = points.stream().map(Point::x).min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal maxX = points.stream().map(Point::x).max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal minY = points.stream().map(Point::y).min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal maxY = points.stream().map(Point::y).max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal two = BigDecimal.valueOf(2);
        return Optional.of(new Point(
                minX.add(maxX).divide(two, CENTER_SCALE, RoundingMode.HALF_UP),
                minY.add(maxY).divide(two, CENTER_SCALE, RoundingMode.HALF_UP)));
    }

    /**
     * Normalize a row label for contiguity comparison: trimmed, with {@code null} mapping to the
     * empty string. Comparison after normalization stays exact (case-sensitive).
     */
    public static String normalizeRowLabel(String rowLabel) {
        return rowLabel == null ? "" : rowLabel.trim();
    }
}
