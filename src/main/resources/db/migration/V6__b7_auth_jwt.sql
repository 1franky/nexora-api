-- B7: autenticación con JWT propio (ver plan.md, sección 11 "Seguridad").
-- El access token es un JWT firmado, no se persiste. El refresh token sí,
-- pero solo su hash — nunca el valor en claro.

CREATE TABLE refresh_tokens (
    id          UUID            PRIMARY KEY,
    user_id     UUID            NOT NULL REFERENCES users (id),
    token_hash  VARCHAR(255)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ     NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
