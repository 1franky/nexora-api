#!/usr/bin/env bash
# Prueba de punta a punta del respaldo y la restauración (la corre el CI; también en local con Docker y restic):
# una base con el esquema REAL de Nexora (las migraciones de Flyway del repositorio) y datos de prueba → respaldar.sh a
# un repositorio restic local → restaurar.sh en una base nueva → misma huella (huella.sql). Si una migración cambia una
# tabla que usa huella.sql, esta prueba falla y obliga a actualizarla.
set -Eeuo pipefail
cd "$(dirname "$0")/../.."

id="prueba-respaldo-$$"
work=$(mktemp -d)
export RESTIC_REPOSITORY="$work/repositorio"
export RESTIC_PASSWORD="contraseña-de-prueba-$$"
export NEXORA_DB_NAME=nexora NEXORA_DB_USERNAME=nexora NEXORA_BACKUP_HOST=prueba NEXORA_CHECK_ALWAYS=1
# La misma imagen de PostgreSQL que compose.yaml: si se sube de versión allá, la prueba la sigue.
image=$(sed -n "s/^[[:space:]]*image:[[:space:]]*['\"]\{0,1\}\(postgres:[^'\"]*\)['\"]\{0,1\}[[:space:]]*$/\1/p" compose.yaml)
[[ -n "$image" ]] || { echo "No encontré la imagen de postgres en compose.yaml" >&2; exit 1; }

cleanup() {
  docker rm -f "$id-origen" "$id-destino" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

start_db() {
  docker run -d --name "$1" -e POSTGRES_DB=nexora -e POSTGRES_USER=nexora -e POSTGRES_PASSWORD=nexora "$image" >/dev/null
  for _ in $(seq 1 60); do
    # pg_isready responde antes de que termine el arranque inicial: se confirma con una consulta real.
    docker exec "$1" psql -U nexora -d nexora -tAc "select 1" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "PostgreSQL no arrancó en $1" >&2
  return 1
}
sql() { docker exec -i "$1" psql -q -v ON_ERROR_STOP=1 -U nexora -d nexora "${@:2}"; }
snapshots() { restic snapshots --json | grep -o '"short_id"' | wc -l | tr -d ' '; }

echo "→ Base de origen ($image) con las migraciones del repositorio"
start_db "$id-origen"
sql "$id-origen" <<'SQL'
create table flyway_schema_history (
  installed_rank int primary key, version varchar(50), description varchar(200) not null, type varchar(20) not null,
  script varchar(1000) not null, checksum int, installed_by varchar(100) not null,
  installed_on timestamp not null default now(), execution_time int not null, success boolean not null);
SQL
rank=0
while read -r version file; do
  rank=$((rank + 1))
  sql "$id-origen" <"$file"
  sql "$id-origen" -c "insert into flyway_schema_history values ($rank, '$version', 'prueba', 'SQL', '$(basename "$file")', 0, 'nexora', now(), 0, true)" </dev/null
done < <(find src/main/resources/db/migration -name 'V*__*.sql' \
  | awk -F/ '{ v = $NF; sub(/^V/, "", v); sub(/__.*/, "", v); print v, $0 }' | sort -n)
echo "  ${rank} migraciones aplicadas"

sql "$id-origen" <<'SQL'
insert into users (id, email, password_hash, display_name, status, created_at, updated_at)
  select ('00000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'u' || g || '@prueba.mx', 'x', 'Usuario ' || g,
         'ACTIVE', now(), now()
  from generate_series(1, 2) g;
-- Referencia circular (users.created_by → users): pg_restore debe resolverla.
update users set created_by = '00000000-0000-0000-0000-000000000001';

insert into accounts (id, user_id, name, type, currency, balance, created_at, updated_at) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Débito', 'DEBIT', 'MXN', 12345.6789, now(), now()),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'Tarjeta', 'CREDIT_CARD', 'MXN', -2500, now(), now());
insert into categories (id, user_id, name, type, created_at, updated_at) values
  ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Súper', 'EXPENSE', now(), now());
insert into transactions (id, account_id, type, amount, balance_effect, date, description, category_id, created_at, updated_at)
  select gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'EXPENSE', g * 1.25, -g * 1.25,
         current_date - g, 'Compra ' || g, '20000000-0000-0000-0000-000000000001', now(), now()
  from generate_series(1, 300) g;
insert into credit_cards (id, account_id, name, bank, last4, credit_limit, closing_day, payment_due_day, created_at, updated_at)
  values ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'Oro', 'Banco', '1234', 50000, 10, 28, now(), now());
insert into transactions (id, account_id, type, amount, balance_effect, date, created_at, updated_at)
  values ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'CREDIT_CARD_PURCHASE', 2500, -2500, current_date, now(), now());
insert into installment_plans (id, credit_card_id, transaction_id, plan_type, original_amount, installment_count,
                               total_amount, installment_amount, start_date, end_date, created_at, updated_at)
  values ('50000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
          'MSI', 2500, 3, 2500, 833.3333, current_date, current_date + 90, now(), now());
insert into installments (id, installment_plan_id, number, due_date, amount, created_at, updated_at)
  select gen_random_uuid(), '50000000-0000-0000-0000-000000000001', g, current_date + g * 30, 833.3333, now(), now()
  from generate_series(1, 3) g;

-- BYTEA: la e.firma cifrada y los XML de CFDI, que viven en la base.
insert into sat_certificate (id, user_id, rfc, certificate_der, private_key_encrypted, password_encrypted, dek_encrypted,
                             valid_until, status, created_at, updated_at)
  values ('60000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'XAXX010101000',
          decode(repeat(md5('cer'), 64), 'hex'), decode(repeat(md5('key'), 40), 'hex'),
          decode(md5('pwd'), 'hex'), decode(md5('dek') || md5('dek2'), 'hex'), now() + interval '2 years', 'ACTIVE', now(), now());
insert into cfdi_invoice (id, user_id, uuid_fiscal, tipo, rfc_emisor, rfc_receptor, fecha_emision, subtotal, iva, total,
                          moneda, xml_content, created_at, updated_at)
  select gen_random_uuid(), '00000000-0000-0000-0000-000000000001', gen_random_uuid()::text, 'RECIBIDA', 'EKU9003173C9',
         'XAXX010101000', now() - g * interval '1 day', g * 100, g * 16, g * 116, 'MXN',
         convert_to('<?xml version="1.0"?><cfdi:Comprobante Total="' || g * 116 || '" Descripción="ñ"/>', 'UTF8')
           || decode(repeat(md5(g::text), 20), 'hex'),
         now(), now()
  from generate_series(1, 50) g;
SQL

# Un GitHub falso para las alertas: guarda los issues que recibe y los devuelve al listarlos.
cat >"$work/github.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
issues = []
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, code, data):
        body = json.dumps(data, indent=2, ensure_ascii=False).encode()  # como GitHub: UTF-8 sin escapar
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(body)
    def do_GET(self): self.reply(200, issues)
    def do_POST(self):
        issue = json.loads(self.rfile.read(int(self.headers["Content-Length"])))  # falla si el JSON no es válido
        issues.append(issue)
        with open(sys.argv[2], "a") as f: f.write(issue["title"] + "\n")
        self.reply(201, issue)
s = HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(s.server_port))
s.serve_forever()
PY
python3 "$work/github.py" "$work/puerto" "$work/issues.txt" &
github_pid=$!
trap 'kill $github_pid 2>/dev/null || true; cleanup' EXIT
for _ in $(seq 1 50); do [[ -s "$work/puerto" ]] && break; sleep 0.1; done
touch "$work/issues.txt"
NEXORA_ALERT_API="http://127.0.0.1:$(cat "$work/puerto")"
export NEXORA_ALERT_API NEXORA_ALERT_REPO=nexora/prueba NEXORA_ALERT_TOKEN=prueba
issues() { wc -l <"$work/issues.txt" | tr -d ' '; }

echo "→ Respaldo, con un tope de tamaño diminuto: avisa pero termina bien"
NEXORA_PG_CONTAINER="$id-origen" NEXORA_MAX_REPO_GB=0.0000001 ops/respaldos/respaldar.sh | tee "$work/respaldo.log"
grep -q "pasó el tope de tamaño" "$work/respaldo.log" || { echo "No avisó del tope de tamaño" >&2; exit 1; }
[[ "$(issues)" == 1 ]] || { echo "Se esperaba un issue de tamaño" >&2; exit 1; }

echo "→ Un segundo respaldo sobre el tope no repite el issue abierto"
NEXORA_PG_CONTAINER="$id-origen" NEXORA_MAX_REPO_GB=0.0000001 ops/respaldos/respaldar.sh >/dev/null
[[ "$(issues)" == 1 ]] || { echo "El issue de tamaño se repitió" >&2; exit 1; }
antes=$(snapshots)

echo "→ Con la contraseña equivocada falla y avisa, sin crear un repositorio nuevo"
if RESTIC_PASSWORD=equivocada NEXORA_PG_CONTAINER="$id-origen" ops/respaldos/respaldar.sh >/dev/null 2>&1; then
  echo "respaldar.sh terminó bien con la contraseña equivocada" >&2
  exit 1
fi
grep -qx "Respaldo fallido: repositorio" "$work/issues.txt" || { echo "No abrió el issue del repositorio" >&2; exit 1; }
[[ "$(snapshots)" == "$antes" ]] || { echo "Cambiaron las instantáneas" >&2; exit 1; }

echo "→ Restauración en una base nueva"
start_db "$id-destino"
NEXORA_RESTORE_PG_CONTAINER="$id-destino" ops/respaldos/restaurar.sh

echo "→ Comparación (huella.sql)"
before=$(sql "$id-origen" -tA <ops/respaldos/huella.sql)
after=$(sql "$id-destino" -tA <ops/respaldos/huella.sql)
grep -qx 'transactions|301' <<<"$before" || { echo "La huella de origen no tiene los datos esperados:"$'\n'"$before" >&2; exit 1; }
grep -qx 'cfdi_invoice|50' <<<"$before" || { echo "La huella de origen no tiene los CFDI esperados" >&2; exit 1; }
[[ "$before" == "$after" ]] || { echo "La base restaurada no coincide:"; diff <(echo "$before") <(echo "$after"); exit 1; } >&2

echo "→ La prueba de restauración trimestral (probar-restauracion.sh) contra este repositorio y la huella del origen"
printf '%s\n' "$before" >"$work/huella-origen.txt"
NEXORA_HUELLA_ESPERADA="$work/huella-origen.txt" ops/respaldos/probar-restauracion.sh >"$work/trimestral.log" 2>&1 \
  || { cat "$work/trimestral.log" >&2; echo "probar-restauracion.sh falló" >&2; exit 1; }
grep -q "igual a la huella de producción" "$work/trimestral.log" || { cat "$work/trimestral.log" >&2; exit 1; }
grep -E '^  ✓' "$work/trimestral.log"

echo "→ Una base que ya tiene el esquema no se sobrescribe"
if NEXORA_RESTORE_PG_CONTAINER="$id-origen" ops/respaldos/restaurar.sh 2>/dev/null; then
  echo "restaurar.sh aceptó una base con datos" >&2
  exit 1
fi

echo "→ Si la base no responde, el respaldo falla, avisa y no guarda nada"
if NEXORA_PG_CONTAINER="$id-no-existe" ops/respaldos/respaldar.sh >/dev/null 2>&1; then
  echo "respaldar.sh terminó bien sin base de datos" >&2
  exit 1
fi
grep -qx "Respaldo fallido: base de datos" "$work/issues.txt" || { echo "No abrió el issue de la base de datos" >&2; exit 1; }
[[ "$(snapshots)" == "$antes" ]] || { echo "Guardó una instantánea sin base de datos" >&2; exit 1; }

echo "✓ Respaldo y restauración verificados ($(grep -c . <<<"$before") líneas de huella iguales)"
