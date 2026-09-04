-- B11: integración con el SAT — descarga de CFDI (facturas) vía e.firma.
-- Ver plan-integracion-sat.md (raíz del workspace) para el diseño completo.
--
-- Envelope encryption (plan, sección 4.1/4.2): cada sat_certificate tiene su
-- propia clave de datos (dek_encrypted) generada al alta, que cifra
-- private_key_encrypted y password_encrypted; la DEK a su vez se cifra con
-- la clave maestra del servidor (NEXORA_SAT_ENCRYPTION_KEY, fuera de la BD).
-- Todo lo cifrado usa AES-256-GCM (ver SatCryptoService) y vive en columnas
-- BYTEA — certificate.cer y llave.key reales pesan unos KB, muy lejos de
-- justificar un object storage aparte.

CREATE TABLE sat_certificate (
    id                       UUID            PRIMARY KEY,
    user_id                  UUID            NOT NULL REFERENCES users (id),
    rfc                      VARCHAR(13)     NOT NULL,
    certificate_der          BYTEA           NOT NULL,
    private_key_encrypted    BYTEA           NOT NULL,
    password_encrypted       BYTEA           NOT NULL,
    dek_encrypted            BYTEA           NOT NULL,
    valid_until              TIMESTAMPTZ     NOT NULL,
    status                   VARCHAR(30)     NOT NULL,
    last_sync_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ     NOT NULL,
    updated_at               TIMESTAMPTZ     NOT NULL,
    created_by               UUID            REFERENCES users (id)
);

-- Un RFC solo puede estar conectado una vez por usuario (alta = reemplazo, no duplicado).
CREATE UNIQUE INDEX ux_sat_certificate_user_rfc ON sat_certificate (user_id, rfc);

CREATE TABLE sat_download_request (
    id                    UUID            PRIMARY KEY,
    sat_certificate_id    UUID            NOT NULL REFERENCES sat_certificate (id) ON DELETE CASCADE,
    tipo                  VARCHAR(20)     NOT NULL,
    fecha_inicio          TIMESTAMPTZ     NOT NULL,
    fecha_fin             TIMESTAMPTZ     NOT NULL,
    id_solicitud_sat      VARCHAR(100),
    estado                VARCHAR(30)     NOT NULL,
    ids_paquetes          TEXT,
    error_message         TEXT,
    created_at            TIMESTAMPTZ     NOT NULL,
    updated_at            TIMESTAMPTZ     NOT NULL,
    created_by            UUID            REFERENCES users (id)
);

CREATE INDEX ix_sat_download_request_certificate ON sat_download_request (sat_certificate_id);

CREATE TABLE cfdi_invoice (
    id               UUID            PRIMARY KEY,
    user_id          UUID            NOT NULL REFERENCES users (id),
    uuid_fiscal      VARCHAR(36)     NOT NULL,
    tipo             VARCHAR(20)     NOT NULL,
    rfc_emisor       VARCHAR(13)     NOT NULL,
    nombre_emisor    VARCHAR(255),
    rfc_receptor     VARCHAR(13)     NOT NULL,
    nombre_receptor  VARCHAR(255),
    fecha_emision    TIMESTAMPTZ     NOT NULL,
    subtotal         NUMERIC(14, 2)  NOT NULL,
    iva              NUMERIC(14, 2)  NOT NULL DEFAULT 0,
    total            NUMERIC(14, 2)  NOT NULL,
    moneda           VARCHAR(3)      NOT NULL,
    forma_pago       VARCHAR(10),
    metodo_pago      VARCHAR(10),
    uso_cfdi         VARCHAR(10),
    estado_sat       VARCHAR(20)     NOT NULL DEFAULT 'VIGENTE',
    xml_content      BYTEA           NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL,
    updated_at       TIMESTAMPTZ     NOT NULL,
    created_by       UUID            REFERENCES users (id)
);

-- Idempotencia: descargar el mismo CFDI dos veces (re-sync, rangos que se
-- traslapan) no debe duplicar filas.
CREATE UNIQUE INDEX ux_cfdi_invoice_user_uuid ON cfdi_invoice (user_id, uuid_fiscal);
CREATE INDEX ix_cfdi_invoice_user_fecha ON cfdi_invoice (user_id, fecha_emision DESC);
