-- B8: Idempotency-Key para escrituras (ver plan.md, sección 16 "Consideraciones
-- para soporte offline (Android)"). Soporta la cola de escritura offline de
-- nexora-android (A8): un reintento con la misma key devuelve la respuesta ya
-- guardada en vez de duplicar el movimiento/compra/pago.

CREATE TABLE idempotency_records (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users (id),
    key_value       VARCHAR(255)    NOT NULL,
    fingerprint     VARCHAR(64)     NOT NULL,
    response_status INTEGER         NOT NULL,
    response_body   TEXT            NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL
);

CREATE UNIQUE INDEX ux_idempotency_records_user_key ON idempotency_records (user_id, key_value);
