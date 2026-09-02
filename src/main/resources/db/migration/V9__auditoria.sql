-- Auditoría (plan.md, sección 13): "created_by" en todas las entidades vía
-- Spring Data JPA Auditing (mismo mecanismo que ya puebla created_at/
-- updated_at, ver BaseEntity y NexoraAuditorAware) + audit_log para los
-- eventos financieros explícitos que además pide el plan (compras, pagos,
-- movimientos, planes MSI/MCI).
--
-- created_by es nullable a propósito: no hay usuario autenticado todavía
-- cuando se auto-registra un usuario nuevo, ni en jobs de sistema sin
-- request HTTP (tipos de cambio cacheados, notificaciones generadas por el
-- scheduler diario) — ver NexoraAuditorAware.

ALTER TABLE users               ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE accounts            ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE credit_cards        ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE categories          ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE transactions        ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE installment_plans   ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE installments        ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE notifications       ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE refresh_tokens      ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE idempotency_records ADD COLUMN created_by UUID REFERENCES users (id);
ALTER TABLE exchange_rates      ADD COLUMN created_by UUID REFERENCES users (id);

-- Log de solo-escritura de eventos financieros (ver AuditLog): sin FK de
-- entity_id a su tabla de origen a propósito — un TRANSACTION_DELETED debe
-- poder seguir apuntando al id de una Transaction que ya no existe.
CREATE TABLE audit_log (
    id           UUID        PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id),
    event_type   VARCHAR(40) NOT NULL,
    entity_type  VARCHAR(40) NOT NULL,
    entity_id    UUID        NOT NULL,
    summary      TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_log_user_occurred ON audit_log (user_id, occurred_at DESC);
