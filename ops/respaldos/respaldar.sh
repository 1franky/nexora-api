#!/usr/bin/env bash
# Respaldo diario de Nexora: la base de PostgreSQL, cifrada con restic, fuera del VPS (Cloudflare R2).
#
# Todo lo de Nexora vive en la base: también la e.firma (cifrada con NEXORA_SAT_ENCRYPTION_KEY) y los XML de los CFDI,
# en columnas BYTEA. No hay volúmenes de archivos que respaldar.
#
# Corre como un usuario del grupo docker (sin root): `pg_dump` se ejecuta dentro del contenedor de PostgreSQL y su
# salida entra directo a restic. Nada pasa por archivos temporales.
#
# Configuración por variables (en el VPS, /etc/nexora/respaldo.env; ver respaldo.env.example):
#   RESTIC_REPOSITORY, RESTIC_PASSWORD_FILE (o RESTIC_PASSWORD), AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
#   NEXORA_PG_CONTAINER (nexora-postgres), NEXORA_DB_NAME (nexora), NEXORA_DB_USERNAME (nexora)
#   NEXORA_KEEP_DAILY (7), NEXORA_KEEP_WEEKLY (4), NEXORA_KEEP_MONTHLY (6)
#   NEXORA_ALERT_REPO y NEXORA_ALERT_TOKEN: si están, un fallo abre un issue en GitHub.
#   NEXORA_MAX_REPO_GB (sin tope si no está) y NEXORA_WARN_REPO_PCT (80): tope de tamaño del repositorio en GB
#     (10^9 bytes). Al pasar el aviso o el tope se abre un issue, pero el respaldo NO se detiene ni borra nada.
set -Eeuo pipefail

PG_CONTAINER="${NEXORA_PG_CONTAINER:-nexora-postgres}"
DB_NAME="${NEXORA_DB_NAME:-nexora}"
DB_USER="${NEXORA_DB_USERNAME:-nexora}"
HOST_TAG="${NEXORA_BACKUP_HOST:-nexora}"
GITHUB_API="${NEXORA_ALERT_API:-https://api.github.com}"
step="inicio"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# Abre un issue con la etiqueta `respaldo` si hay token; si ya hay uno abierto con el mismo título, no lo repite (el
# aviso de tamaño se repetiría cada día). Nunca hace fallar al respaldo. El cuerpo va ya escapado para JSON.
# El repositorio es público: el issue no lleva datos, rutas ni el nombre del servidor.
open_issue() {
  local title=$1 body=$2
  [[ -n "${NEXORA_ALERT_REPO:-}" && -n "${NEXORA_ALERT_TOKEN:-}" ]] || return 0
  local auth=(-H "Authorization: Bearer ${NEXORA_ALERT_TOKEN}" -H "Accept: application/vnd.github+json")
  if curl -fsS "${auth[@]}" "${GITHUB_API}/repos/${NEXORA_ALERT_REPO}/issues?labels=respaldo&state=open&per_page=100" \
    2>/dev/null | grep -qF "\"${title}\""; then
    log "Ya hay un issue abierto: «${title}»"
    return 0
  fi
  curl -fsS -X POST "${auth[@]}" "${GITHUB_API}/repos/${NEXORA_ALERT_REPO}/issues" \
    -d "{\"title\":\"${title}\",\"body\":\"${body}\",\"labels\":[\"respaldo\"]}" >/dev/null \
    || log "No se pudo abrir el issue de alerta."
}

alert() {
  local code=$1
  log "FALLÓ en el paso «${step}» (código ${code})"
  local body
  # shellcheck disable=SC2016  # las comillas invertidas son Markdown del issue, no expansiones.
  body=$(printf 'El respaldo de Nexora falló en el paso **%s** (código %s) el %s.\\n\\nRegistro: `journalctl -u nexora-respaldo --since today`.\\nRunbook: `ops/respaldos/README.md`.' \
    "$step" "$code" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")
  open_issue "Respaldo fallido: ${step}" "$body"
  exit "$code"
}
trap 'alert $?' ERR

step="repositorio"
# Se crea el repositorio solo si restic dice que NO existe (código 10, restic ≥ 0.17). Cualquier otro error (red,
# credenciales de R2, contraseña equivocada: 12, lock: 11) es una falla y abre el issue, no un `init` a ciegas.
if restic cat config >/dev/null 2>&1; then
  :
else
  rc=$?
  if [[ "$rc" == 10 ]]; then
    log "Creando el repositorio de restic"
    restic init
  else
    log "No se pudo abrir el repositorio de restic (código ${rc})"
    restic cat config >/dev/null  # repite para dejar el error de restic en el registro; el trap abre el issue
  fi
fi

step="base de datos"
log "Respaldando la base ${DB_NAME}"
# Formato custom de pg_dump: comprimido y restaurable con pg_restore. Es una foto consistente (una sola transacción).
# Si pg_dump falla, restic falla y no guarda una instantánea a medias.
restic backup --host "$HOST_TAG" --tag nexora --tag db --stdin-filename nexora-db.dump \
  --stdin-from-command -- docker exec "$PG_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc

step="retención"
restic forget --host "$HOST_TAG" --tag nexora --group-by host,tags --prune \
  --keep-daily "${NEXORA_KEEP_DAILY:-7}" --keep-weekly "${NEXORA_KEEP_WEEKLY:-4}" --keep-monthly "${NEXORA_KEEP_MONTHLY:-6}"

# Tope de tamaño: se mide después de la retención, que es lo que de verdad ocupa el bucket. Solo avisa: no detiene el
# respaldo (se perdería el RPO) ni recorta la retención (podría borrar el respaldo bueno de antes de un bug).
if [[ -n "${NEXORA_MAX_REPO_GB:-}" ]]; then
  step="tamaño"
  # raw-data: los blobs comprimidos y deduplicados, sin contar referencias repetidas. R2 suma algo de índice encima.
  bytes=$(restic stats --mode raw-data --json | sed -n 's/.*"total_size":\([0-9]*\).*/\1/p')
  [[ -n "$bytes" ]]
  read -r size_gb pct level < <(awk -v b="$bytes" -v max="$NEXORA_MAX_REPO_GB" -v warn="${NEXORA_WARN_REPO_PCT:-80}" \
    'BEGIN { p = b / (max * 1e9) * 100; printf "%.3f %.0f %s\n", b / 1e9, p, (p >= 100 ? "tope" : (p >= warn ? "aviso" : "ok")) }')
  [[ -n "${level:-}" ]]
  log "Repositorio: ${size_gb} GB de ${NEXORA_MAX_REPO_GB} GB (${pct} %)"
  if [[ "$level" != ok ]]; then
    if [[ "$level" == tope ]]; then title="Respaldo: el repositorio pasó el tope de tamaño"; else
      title="Respaldo: el repositorio pasó el ${NEXORA_WARN_REPO_PCT:-80} % del tope de tamaño"; fi
    log "AVISO: ${title}. El respaldo sigue; revisa la retención o el tope."
    # shellcheck disable=SC2016  # las comillas invertidas son Markdown del issue, no expansiones.
    body=$(printf 'El repositorio de respaldos ocupa **%s GB** de un tope de %s GB (**%s %%**), el %s.\\n\\nEl respaldo sigue corriendo y no se borró nada. Opciones: bajar la retención (`NEXORA_KEEP_*`) o subir el tope (`NEXORA_MAX_REPO_GB`) en `/etc/nexora/respaldo.env`, o revisar por qué creció (`restic stats --mode raw-data`, `restic snapshots`).\\nRunbook: `ops/respaldos/README.md`.' \
      "$size_gb" "$NEXORA_MAX_REPO_GB" "$pct" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")
    open_issue "$title" "$body"
  fi
fi

# Los domingos (o si se pide), una verificación que lee una muestra de los datos: detecta corrupción en el destino.
if [[ "$(date +%u)" == 7 || -n "${NEXORA_CHECK_ALWAYS:-}" ]]; then
  step="verificación"
  restic check --read-data-subset=5%
fi

step="fin"
log "Respaldo completo"
