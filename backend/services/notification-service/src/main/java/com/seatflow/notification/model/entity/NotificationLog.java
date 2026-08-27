package com.seatflow.notification.model.entity;

import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.model.enums.NotificationTemplateType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "notification_logs",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_notifications_idempotency", columnNames = {"idempotency_key"})
    },
    indexes = {
        @Index(name = "idx_notif_recipient_created", columnList = "recipient_email, created_at DESC")
    }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "recipient_email", nullable = false, updatable = false)
    @ToString.Include
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, updatable = false, length = 100)
    @ToString.Include
    private NotificationTemplateType templateType;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(name = "idempotency_key", unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "rendered_content", columnDefinition = "TEXT")
    private String renderedContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @ToString.Include
    private NotificationStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

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
        NotificationLog that = (NotificationLog) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
