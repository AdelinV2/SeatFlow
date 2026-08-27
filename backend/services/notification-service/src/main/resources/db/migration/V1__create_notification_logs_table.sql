CREATE TABLE notification_logs (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    recipient_email VARCHAR(255) NOT NULL,
    template_type   VARCHAR(100) NOT NULL,
    subject         VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(255),
    rendered_content TEXT,
    status          VARCHAR(30)  NOT NULL,
    error_message   TEXT,
    sent_at         TIMESTAMPTZ,
    retry_count     INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_logs PRIMARY KEY (id),
    CONSTRAINT uq_notifications_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_notif_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notif_retries CHECK (retry_count >= 0 AND retry_count <= 5),
    CONSTRAINT chk_notif_email CHECK (recipient_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

CREATE INDEX idx_notif_recipient_created ON notification_logs(recipient_email, created_at DESC);
CREATE INDEX idx_notif_pending_retry ON notification_logs(created_at ASC)
    WHERE status = 'FAILED' AND retry_count < 3;
