-- Caché de tipos de cambio a la moneda base de la app (MXN, ver
-- AccountService.getBalanceSummary y ExchangeRateService) para que las
-- cuentas en otras monedas se conviertan de verdad al agregarse en el
-- disponible/patrimonio, en vez de sumarse tal cual (bug corregido acá).
-- No hay historial: una fila por moneda, con su último valor conocido.

CREATE TABLE exchange_rates (
    id              UUID            PRIMARY KEY,
    currency        VARCHAR(3)      NOT NULL UNIQUE,
    rate_to_base    NUMERIC(19, 6)  NOT NULL CHECK (rate_to_base > 0),
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL
);
