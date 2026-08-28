-- B3: Tarjetas de crédito (ver plan.md, secciones 4, 5, 7 y 8).
--
-- No se agregan tipos nuevos a transactions.type: CREDIT_CARD_PURCHASE y
-- CREDIT_CARD_PAYMENT ya estaban permitidos desde V2. Solo se agrega la
-- columna "merchant" (comercio de la compra) y la tabla credit_cards.

ALTER TABLE transactions ADD COLUMN merchant VARCHAR(255);

CREATE TABLE credit_cards (
    id                  UUID            PRIMARY KEY,
    account_id          UUID            NOT NULL UNIQUE REFERENCES accounts (id),
    name                VARCHAR(255)    NOT NULL,
    bank                VARCHAR(255)    NOT NULL,
    last4               VARCHAR(4)      NOT NULL,
    credit_limit        NUMERIC(19, 4)  NOT NULL CHECK (credit_limit > 0),
    closing_day         INTEGER         NOT NULL CHECK (closing_day BETWEEN 1 AND 28),
    payment_due_day     INTEGER         NOT NULL CHECK (payment_due_day BETWEEN 1 AND 28),
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ     NOT NULL
);
