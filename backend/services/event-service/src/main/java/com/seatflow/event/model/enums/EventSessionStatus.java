package com.seatflow.event.model.enums;

/**
 * Lifecycle of one concrete bookable showing of an event.
 *
 * <p>An Event is catalog/content identity while an EventSession is the inventory
 * boundary introduced in Phase 12 (ADR-011). Cancelling one session never cancels
 * the whole event unless an explicit admin workflow decides so.
 */
public enum EventSessionStatus {
    SCHEDULED,
    CANCELLED,
    COMPLETED
}
