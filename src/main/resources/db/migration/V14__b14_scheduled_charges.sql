-- B14: cargos programados (suscripciones / domiciliaciones).
-- Ver plan-cargos-programados.md (raíz del workspace).
--
-- Una regla ("$119, Disney+, tarjeta X, cada día 15") que
-- ScheduledChargeService convierte en un movimiento real en su fecha.
-- next_run_date es el cursor: el job registra mientras next_run_date <= hoy
-- y lo avanza a la siguiente ocurrencia, así los cargos atrasados salen
-- solos y borrar un movimiento generado no lo vuelve a generar.

CREATE TABLE scheduled_charges (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users (id),
    account_id      UUID            NOT NULL REFERENCES accounts (id),
    name            VARCHAR(120)    NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    category_id     UUID            REFERENCES categories (id),
    description     VARCHAR(500),
    frequency       VARCHAR(20)     NOT NULL CHECK (frequency IN ('MONTHLY', 'YEARLY')),
    day_of_month    INTEGER         NOT NULL CHECK (day_of_month BETWEEN 1 AND 31),
    month_of_year   INTEGER         CHECK (month_of_year BETWEEN 1 AND 12),
    start_date      DATE            NOT NULL,
    end_date        DATE,
    next_run_date   DATE,
    last_run_date   DATE,
    status          VARCHAR(20)     NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'FINISHED', 'CANCELLED')),
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL,
    created_by      UUID            REFERENCES users (id),
    -- El mes solo aplica (y es obligatorio) para cargos anuales.
    CHECK ((frequency = 'YEARLY') = (month_of_year IS NOT NULL)),
    CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_scheduled_charges_due ON scheduled_charges (next_run_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_scheduled_charges_user ON scheduled_charges (user_id);

-- Movimientos generados por un cargo programado (para mostrar el distintivo "Programado").
ALTER TABLE transactions ADD COLUMN scheduled_charge_id UUID REFERENCES scheduled_charges (id);
CREATE INDEX idx_transactions_scheduled_charge ON transactions (scheduled_charge_id) WHERE scheduled_charge_id IS NOT NULL;

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;

ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
    'PAYMENT_DUE', 'PAYMENT_DUE_SOON', 'PAYMENT_OVERDUE',
    'INSTALLMENT_DUE', 'BUDGET_EXCEEDED', 'UNUSUAL_EXPENSE',
    'SAT_SYNC_COMPLETED', 'SAT_SYNC_FAILED',
    'SCHEDULED_CHARGE_POSTED', 'SCHEDULED_CHARGE_FAILED'
));
