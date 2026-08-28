-- B4: Compras a MSI/MCI (ver plan.md, sección 6 y sección 14 "Modelo de
-- datos inicial").

CREATE TABLE installment_plans (
    id                  UUID            PRIMARY KEY,
    credit_card_id      UUID            NOT NULL REFERENCES credit_cards (id),
    transaction_id      UUID            NOT NULL REFERENCES transactions (id),
    plan_type           VARCHAR(10)     NOT NULL CHECK (plan_type IN ('MSI', 'MCI')),
    original_amount     NUMERIC(19, 4)  NOT NULL CHECK (original_amount > 0),
    installment_count   INTEGER         NOT NULL CHECK (installment_count >= 2),
    interest_rate       NUMERIC(9, 4)   NOT NULL DEFAULT 0 CHECK (interest_rate >= 0),
    interest_amount     NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    total_amount        NUMERIC(19, 4)  NOT NULL,
    installment_amount  NUMERIC(19, 4)  NOT NULL,
    start_date          DATE            NOT NULL,
    end_date            DATE            NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ     NOT NULL
);

CREATE INDEX ix_installment_plans_credit_card_id ON installment_plans (credit_card_id);

CREATE TABLE installments (
    id                      UUID            PRIMARY KEY,
    installment_plan_id     UUID            NOT NULL REFERENCES installment_plans (id),
    number                  INTEGER         NOT NULL,
    due_date                DATE            NOT NULL,
    amount                  NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID')),
    paid_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    UNIQUE (installment_plan_id, number)
);

CREATE INDEX ix_installments_plan_id ON installments (installment_plan_id);
