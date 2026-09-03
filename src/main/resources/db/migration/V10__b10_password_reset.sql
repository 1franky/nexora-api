-- B10: recuperación de contraseña por OTP enviado por email. code_hash usa
-- BCrypt (no SHA-256): un código de 6 dígitos tiene poca entropía, y BCrypt
-- es deliberadamente lento contra fuerza bruta offline si la tabla se filtra.

CREATE TABLE password_reset_codes (
    id          UUID            PRIMARY KEY,
    user_id     UUID            NOT NULL REFERENCES users (id),
    code_hash   VARCHAR(255)    NOT NULL,
    expires_at  TIMESTAMPTZ     NOT NULL,
    used_at     TIMESTAMPTZ,
    attempts    INT             NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ     NOT NULL,
    -- BaseEntity ya trae created_by (nullable) desde V9 auditoría — a diferencia de
    -- las tablas de esa migración, esta es nueva, así que va directo en el CREATE.
    created_by  UUID            REFERENCES users (id)
);

CREATE INDEX ix_password_reset_codes_user_id ON password_reset_codes (user_id);
