-- Huella de los datos de Nexora: la misma consulta en producción y en una copia restaurada debe dar el mismo resultado
-- si nadie movió nada después de la instantánea. Solo lectura. NO imprime datos personales ni financieros: solo
-- conteos y resúmenes md5 (los importes y los XML entran al md5, nunca a la salida).
--
-- Se dejan fuera las tablas que cambian solas (refresh_tokens, idempotency_records, audit_log, password_reset_codes,
-- notifications, sat_download_request) y las columnas de fechas de sincronización: la huella nunca coincidiría.
--
--   VPS:   docker exec -i nexora-postgres psql -U nexora -d nexora -tA < ops/respaldos/huella.sql > huella-produccion.txt
select 'users|'                  || count(*) from users
union all select 'accounts|'               || count(*) from accounts
union all select 'categories|'             || count(*) from categories
union all select 'transactions|'           || count(*) from transactions
union all select 'credit_cards|'           || count(*) from credit_cards
union all select 'installment_plans|'      || count(*) from installment_plans
union all select 'installments|'           || count(*) from installments
union all select 'scheduled_charges|'      || count(*) from scheduled_charges
union all select 'cfdi_invoice|'           || count(*) from cfdi_invoice
union all select 'sat_certificate|'        || count(*) from sat_certificate
union all select 'sat_contraparte_rfc|'    || count(*) from sat_contraparte_rfc
union all select 'flyway_ultima|'          || max(version::int) from flyway_schema_history where success
union all select 'ultimo_movimiento|'      || coalesce(max(created_at)::text, '-') from transactions
union all select 'md5_cuentas|'            || coalesce(md5(string_agg(id::text || ':' || balance::text || ':' || status, ',' order by id)), '-') from accounts
union all select 'md5_movimientos|'        || coalesce(md5(string_agg(id::text || ':' || amount::text || ':' || balance_effect::text || ':' || status, ',' order by id)), '-') from transactions
union all select 'md5_cuotas|'             || coalesce(md5(string_agg(id::text || ':' || amount::text || ':' || status, ',' order by id)), '-') from installments
union all select 'md5_cfdi|'               || coalesce(md5(string_agg(md5(xml_content), ',' order by id)), '-') from cfdi_invoice
union all select 'md5_efirma|'             || coalesce(md5(string_agg(md5(certificate_der) || md5(private_key_encrypted) || md5(password_encrypted) || md5(dek_encrypted), ',' order by id)), '-') from sat_certificate;
