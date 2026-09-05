-- B12: RFCs de contraparte para sincronizar CFDI RECIBIDAS del SAT.
-- Ver plan-integracion-sat.md (raíz del workspace).
--
-- El Web Service de Descarga Masiva del SAT exige, para RECIBIDAS, el RFC
-- del emisor específico a consultar (no existe "todos mis recibidos" en una
-- sola solicitud — ver SatWsDescargaMasivaClient.solicitarDescarga). Esta
-- tabla guarda los RFC que el propio usuario registra (su empleador, sus
-- proveedores de servicios, etc.); SatSyncService sincroniza RECIBIDAS una
-- vez por cada RFC de esta lista.

CREATE TABLE sat_contraparte_rfc (
    id            UUID            PRIMARY KEY,
    user_id       UUID            NOT NULL REFERENCES users (id),
    rfc           VARCHAR(13)     NOT NULL,
    alias         VARCHAR(100),
    created_at    TIMESTAMPTZ     NOT NULL,
    updated_at    TIMESTAMPTZ     NOT NULL,
    created_by    UUID            REFERENCES users (id)
);

-- El mismo RFC de contraparte no se registra dos veces por usuario.
CREATE UNIQUE INDEX ux_sat_contraparte_rfc_user_rfc ON sat_contraparte_rfc (user_id, rfc);

-- Trazabilidad: qué contraparte se consultó en cada solicitud de RECIBIDAS (null en EMITIDAS).
ALTER TABLE sat_download_request ADD COLUMN rfc_contraparte VARCHAR(13);
