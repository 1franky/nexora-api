-- B15: presupuestos mensuales (plan.md, sección 22; WC-28).
--
-- Un presupuesto es un tope mensual en MXN: por categoría de gasto
-- (category_id) o general para todo el gasto del mes (category_id NULL).
-- Cada mes empieza con el monto completo: el sobrante no se acumula.

CREATE TABLE budgets (
    id          UUID            PRIMARY KEY,
    user_id     UUID            NOT NULL REFERENCES users (id),
    category_id UUID            REFERENCES categories (id),
    amount      NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ     NOT NULL,
    created_by  UUID            REFERENCES users (id)
);

-- Uno por categoría y uno general por usuario.
CREATE UNIQUE INDEX ux_budgets_user_category ON budgets (user_id, category_id) WHERE category_id IS NOT NULL;
CREATE UNIQUE INDEX ux_budgets_user_general ON budgets (user_id) WHERE category_id IS NULL;

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;

ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
    'PAYMENT_DUE', 'PAYMENT_DUE_SOON', 'PAYMENT_OVERDUE',
    'INSTALLMENT_DUE', 'BUDGET_NEAR_LIMIT', 'BUDGET_EXCEEDED', 'UNUSUAL_EXPENSE',
    'SAT_SYNC_COMPLETED', 'SAT_SYNC_FAILED',
    'SCHEDULED_CHARGE_POSTED', 'SCHEDULED_CHARGE_FAILED'
));
