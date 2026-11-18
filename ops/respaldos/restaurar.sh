#!/usr/bin/env bash
# Restaura un respaldo de Nexora en una base de datos VACÍA.
#
#   ops/respaldos/restaurar.sh [instantánea]   (por omisión, la más reciente)
#
# Usa las mismas variables RESTIC_* que respaldar.sh, más:
#   NEXORA_RESTORE_PG_CONTAINER (nexora-postgres), NEXORA_DB_NAME (nexora), NEXORA_DB_USERNAME (nexora)
# La API debe estar detenida mientras se restaura. Ver el runbook en ops/respaldos/README.md.
#
# La e.firma restaurada sigue cifrada con NEXORA_SAT_ENCRYPTION_KEY: la API que use esta base necesita la MISMA llave
# (está en el gestor de contraseñas). Sin ella, cada usuario tiene que volver a subir su e.firma.
set -Eeuo pipefail

PG_CONTAINER="${NEXORA_RESTORE_PG_CONTAINER:-nexora-postgres}"
DB_NAME="${NEXORA_DB_NAME:-nexora}"
DB_USER="${NEXORA_DB_USERNAME:-nexora}"
SNAPSHOT="${1:-latest}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# Por seguridad, no se mezcla con datos existentes: si Flyway ya creó su tabla, la base no está vacía (esto también
# protege contra una API que arrancó antes de tiempo y migró una base nueva).
existing=$(docker exec "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'flyway_schema_history'")
if [[ "$existing" != "0" ]]; then
  echo "La base ${DB_NAME} en ${PG_CONTAINER} ya tiene el esquema de Nexora: restaura en una base vacía." >&2
  exit 1
fi

log "Restaurando la base desde la instantánea ${SNAPSHOT}"
# --exit-on-error: una restauración a medias no cuenta como restaurada.
restic dump --tag nexora,db "$SNAPSHOT" /nexora-db.dump \
  | docker exec -i "$PG_CONTAINER" pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner --exit-on-error

log "Restauración completa. Arranca la API (con la misma NEXORA_SAT_ENCRYPTION_KEY) y revisa /actuator/health."
