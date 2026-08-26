package com.seatflow.payment.model.entity;

import com.seatflow.payment.model.enums.PaymentStatus;
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
    name = "payments",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payments_idempotency_key", columnNames = {"idempotency_key"}),
        @UniqueConstraint(name = "uq_payments_reservation_id", columnNames = {"reservation_id"})
    },
    indexes = {
        @Index(name = "idx_payments_reservation_id", columnList = "reservation_id"),
        @Index(name = "idx_payments_user_id", columnList = "user_id"),
        @Index(name = "idx_payments_customer_email", columnList = "customer_email"),
        @Index(name = "idx_payments_status_created", columnList = "status, created_at"),
        @Index(name = "idx_payments_event_id", columnList = "event_id")
    }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID reservationId;

    @Column(name = "user_id")
    @ToString.Include
    private UUID userId; // Nullable for guest checkouts (ADR-001)

    @Column(name = "customer_email", nullable = false, updatable = false)
    @ToString.Include
    private String customerEmail;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "stripe_payment_intent_id")
    @ToString.Include
    private String stripePaymentIntentId;

    @Column(name = "client_secret", updatable = false)
    private String clientSecret;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "tax_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

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
        Payment payment = (Payment) o;
        return getId() != null && Objects.equals(getId(), payment.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
