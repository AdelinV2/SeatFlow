package com.seatflow.ticket.model.entity;

import com.seatflow.ticket.model.enums.ValidationResult;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "ticket_validations",
    indexes = {
        @Index(name = "idx_validations_ticket_id", columnList = "ticket_id, scanned_at"),
        @Index(name = "idx_validations_device", columnList = "scanner_device_id, scanned_at")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class TicketValidation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "ticket_id", updatable = false)
    @ToString.Include
    private UUID ticketId; // Nullable for invalid/unrecognized QR scans

    @Column(name = "scanner_device_id", nullable = false, length = 100, updatable = false)
    @ToString.Include
    private String scannerDeviceId;

    @Column(name = "scan_result", nullable = false, length = 30, updatable = false)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private ValidationResult scanResult;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String details;

    @CreationTimestamp
    @Column(name = "scanned_at", nullable = false, updatable = false)
    @ToString.Include
    private Instant scannedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        TicketValidation that = (TicketValidation) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
