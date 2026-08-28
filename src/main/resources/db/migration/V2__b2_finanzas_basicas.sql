-- B2: Usuarios, cuentas, categorías y movimientos (ver plan.md, secciones
-- 2, 3 y 14 "Modelo de datos inicial").

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    display_name    VARCHAR(255)    NOT NULL,
    status          VARCHAR(20)     NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL
);

CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE accounts (
    id                              UUID            PRIMARY KEY,
    user_id                         UUID            NOT NULL REFERENCES users (id),
    name                            VARCHAR(255)    NOT NULL,
    type                            VARCHAR(20)     NOT NULL
                                        CHECK (type IN ('DEBIT', 'SAVINGS', 'CREDIT_CARD', 'AFORE', 'PPR')),
    currency                        VARCHAR(3)      NOT NULL,
    balance                         NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    include_in_available_balance    BOOLEAN         NOT NULL DEFAULT TRUE,
    include_in_net_worth            BOOLEAN         NOT NULL DEFAULT TRUE,
    status                          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
                                        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version                         BIGINT          NOT NULL DEFAULT 0,
    created_at                      TIMESTAMPTZ     NOT NULL,
    updated_at                      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX ix_accounts_user_id ON accounts (user_id);

CREATE TABLE categories (
    id          UUID            PRIMARY KEY,
    user_id     UUID            NOT NULL REFERENCES users (id),
    name        VARCHAR(255)    NOT NULL,
    type        VARCHAR(20)     NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    status      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX ix_categories_user_id ON categories (user_id);

CREATE TABLE transactions (
    id                  UUID            PRIMARY KEY,
    account_id          UUID            NOT NULL REFERENCES accounts (id),
    type                VARCHAR(30)     NOT NULL CHECK (type IN (
                            'INCOME', 'EXPENSE', 'TRANSFER',
                            'CREDIT_CARD_PURCHASE', 'CREDIT_CARD_PAYMENT',
                            'REFUND', 'ADJUSTMENT'
                        )),
    amount              NUMERIC(19, 4)  NOT NULL CHECK (amount > 0),
    balance_effect      NUMERIC(19, 4)  NOT NULL,
    date                DATE            NOT NULL,
    description         VARCHAR(500),
    reference           VARCHAR(255),
    category_id         UUID            REFERENCES categories (id),
    transfer_group_id   UUID,
    counter_account_id  UUID            REFERENCES accounts (id),
    status              VARCHAR(20)     NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'VOIDED')),
    created_at          TIMESTAMPTZ     NOT NULL,
    updated_at          TIMESTAMPTZ     NOT NULL
);

CREATE INDEX ix_transactions_account_id ON transactions (account_id);
CREATE INDEX ix_transactions_category_id ON transactions (category_id);
CREATE INDEX ix_transactions_transfer_group_id ON transactions (transfer_group_id);
