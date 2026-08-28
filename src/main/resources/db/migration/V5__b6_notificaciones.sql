-- B6: Notificaciones (ver plan.md, sección 9).

CREATE TABLE notifications (
    id                  UUID            PRIMARY KEY,
    user_id             UUID            NOT NULL REFERENCES users (id),
    type                VARCHAR(30)     NOT NULL CHECK (type IN (
                            'PAYMENT_DUE', 'PAYMENT_DUE_SOON', 'PAYMENT_OVERDUE',
                            'INSTALLMENT_DUE', 'BUDGET_EXCEEDED', 'UNUSUAL_EXPENSE'
                        )),
    title               VARCHAR(255)    NOT NULL,
    message             VARCHAR(1000)   NOT NULL,
    related_entity_id   UUID,
    for_date            DATE            NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'UNREAD' CHECK (status IN ('UNREAD', 'READ')),
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ     NOT NULL
);

CREATE INDEX ix_notifications_user_id ON notifications (user_id);

-- Soporta la verificación de "ya se generó esta notificación para este día" (evita duplicados).
CREATE UNIQUE INDEX ux_notifications_dedup ON notifications (user_id, type, related_entity_id, for_date);
