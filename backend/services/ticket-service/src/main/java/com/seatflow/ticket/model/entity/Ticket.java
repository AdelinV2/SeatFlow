package com.seatflow.ticket.model.entity;

import com.seatflow.ticket.model.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "tickets",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_tickets_ticket_code", columnNames = {"ticket_code"}),
        @UniqueConstraint(name = "uq_tickets_reservation_seat", columnNames = {"reservation_id", "seat_id"})
    },
    indexes = {
        @Index(name = "idx_tickets_event_status", columnList = "event_id, status"),
        @Index(name = "idx_tickets_user_id", columnList = "user_id"),
        @Index(name = "idx_tickets_customer_email", columnList = "customer_email"),
        @Index(name = "idx_tickets_reservation_id", columnList = "reservation_id"),
        @Index(name = "idx_tickets_payment_id", columnList = "payment_id")
    }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID reservationId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID paymentId;

    @Column(name = "user_id", updatable = true)
    @ToString.Include
    private UUID userId; // Nullable for guest checkouts (ADR-001)

    @Column(name = "customer_email", nullable = false, updatable = false)
    @ToString.Include
    private String customerEmail;

    @Column(name = "attendee_name")
    private String attendeeName;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "seat_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID seatId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price; // Gross tax-inclusive amount

    @Column(name = "tax_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO; // Tax portion (ADR-004)

    @Column(name = "net_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal netAmount = BigDecimal.ZERO; // Net base price (ADR-004)

    @Column(name = "ticket_code", nullable = false, unique = true, length = 64, updatable = false)
    @ToString.Include
    private String ticketCode;

    @Column(name = "qr_code_data", nullable = false, columnDefinition = "TEXT")
    private String qrCodeData;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @ToString.Include
    private TicketStatus status = TicketStatus.VALID;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Ticket ticket = (Ticket) o;
        return getId() != null && Objects.equals(getId(), ticket.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
