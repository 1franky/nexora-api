#!/usr/bin/env bash
# Prueba de restauración REAL: restaura una instantánea del repositorio de restic (el bucket de R2) en una base
# desechable, en una máquina aparte, y comprueba que los datos sirven. Cada tres meses (ops/respaldos/README.md).
#
#   set -a; source respaldo.env; set +a
#   ops/respaldos/probar-restauracion.sh [instantánea]   (por omisión, la más reciente)
#
# Solo lee el repositorio: nunca respalda ni hace forget/prune. `restic check` toma un lock exclusivo, así que no lo
# corras cerca de las 4:15 (hora del respaldo en el VPS).
#
# Variables opcionales:
#   NEXORA_HUELLA_ESPERADA   archivo con la salida de huella.sql en producción: se compara con la copia restaurada.
#   NEXORA_CHECK_SUBSET      porcentaje para `restic check --read-data-subset` (por omisión se lee el 100 %).
#   NEXORA_CONSERVAR=1       no borra la base al terminar (para levantar la API contra la copia).
set -Eeuo pipefail
cd "$(dirname "$0")/../.."

: "${RESTIC_REPOSITORY:?Carga primero el respaldo.env (set -a; source respaldo.env; set +a)}"

SNAPSHOT="${1:-latest}"
pg="nexora-prueba-restauracion-pg"
image=$(sed -n "s/^[[:space:]]*image:[[:space:]]*['\"]\{0,1\}\(postgres:[^'\"]*\)['\"]\{0,1\}[[:space:]]*$/\1/p" compose.yaml)
work=$(mktemp -d)
fallas=0

paso() { printf '\n→ %s\n' "$*"; }
bien() { printf '  ✓ %s\n' "$*"; }
mal() { printf '  ✗ %s\n' "$*" >&2; fallas=$((fallas + 1)); }
# Un conteo de problemas: 0 es bien, cualquier otro número es una falla.
cero() { if [[ "$1" == 0 ]]; then bien "$2"; else mal "$3"; fi; }
sql() { docker exec -i "$pg" psql -U nexora -d nexora -v ON_ERROR_STOP=1 -tA "$@"; }

cleanup() {
  rm -rf "$work"
  if [[ -n "${NEXORA_CONSERVAR:-}" ]]; then
    printf '\nSe conserva %s. Al terminar:\n  docker rm -f %s\n' "$pg" "$pg"
  else
    docker rm -f "$pg" >/dev/null 2>&1 || true
  fi
}

[[ -n "$image" ]] || { echo "No encontré la imagen de postgres en compose.yaml" >&2; exit 1; }
# Sin restos de una corrida anterior: restaurar.sh se niega a escribir sobre datos, y la prueba debe partir de cero.
if docker inspect "$pg" >/dev/null 2>&1; then
  echo "Ya existe $pg (¿una prueba anterior con NEXORA_CONSERVAR?). Bórralo primero: docker rm -f $pg" >&2
  exit 1
fi
trap cleanup EXIT

paso "Instantáneas en el repositorio"
restic snapshots --tag nexora --latest 3

paso "Integridad del repositorio"
if [[ -n "${NEXORA_CHECK_SUBSET:-}" ]]; then
  restic check --read-data-subset="${NEXORA_CHECK_SUBSET}%"
else
  restic check --read-data
fi

paso "Base desechable ($image)"
# Sin volumen con nombre: los datos viven en el contenedor y desaparecen con él.
docker run -d --name "$pg" -e POSTGRES_DB=nexora -e POSTGRES_USER=nexora \
  -e POSTGRES_PASSWORD="$(openssl rand -hex 16)" "$image" >/dev/null
for _ in $(seq 1 60); do
  # pg_isready responde antes de que termine el arranque inicial: se confirma con una consulta real.
  sql -c "select 1" >/dev/null 2>&1 && break
  sleep 1
done
sql -c "select 1" >/dev/null

paso "Restauración"
inicio=$(date +%s)
NEXORA_RESTORE_PG_CONTAINER="$pg" ops/respaldos/restaurar.sh "$SNAPSHOT"
duracion=$(($(date +%s) - inicio))
bien "restaurado en ${duracion} s"

paso "Esquema"
fallidas=$(sql -c "select count(*) from flyway_schema_history where not success")
version=$(sql -c "select max(version::int) from flyway_schema_history where success")
cero "$fallidas" "Flyway en la versión ${version}, sin migraciones fallidas" "${fallidas} migraciones fallidas"
# pg_restore --exit-on-error ya falló si alguna llave foránea o índice único no se pudo recrear; aquí se confirma que
# todas las restricciones quedaron validadas.
sin_validar=$(sql -c "select count(*) from pg_constraint c join pg_namespace n on n.oid = c.connamespace
                      where n.nspname = 'public' and not c.convalidated")
cero "$sin_validar" "todas las restricciones validadas" "${sin_validar} restricciones sin validar"

paso "Datos"
usuarios=$(sql -c "select count(*) from users")
if [[ "$usuarios" -gt 0 ]]; then bien "${usuarios} usuarios"; else mal "la base restaurada no tiene usuarios"; fi
movimientos=$(sql -c "select count(*) from transactions")
bien "${movimientos} movimientos"
read -r efirmas efirmas_vacias < <(sql -F ' ' -c "
  select count(*),
         count(*) filter (where least(octet_length(certificate_der), octet_length(private_key_encrypted),
                                      octet_length(password_encrypted), octet_length(dek_encrypted)) = 0)
  from sat_certificate")
cero "$efirmas_vacias" "${efirmas} e.firmas con sus cuatro partes" "${efirmas_vacias} e.firmas con alguna parte vacía"
read -r cfdis cfdis_raros < <(sql -F ' ' -c "
  select count(*), count(*) filter (where position('Comprobante'::bytea in xml_content) = 0) from cfdi_invoice")
cero "$cfdis_raros" "${cfdis} CFDI con su XML" "${cfdis_raros} CFDI cuyo XML no tiene un Comprobante"

paso "Huella"
sql <ops/respaldos/huella.sql >"$work/huella.txt"
sed 's/|/: /' "$work/huella.txt" | sed 's/^/  /'
if [[ -n "${NEXORA_HUELLA_ESPERADA:-}" ]]; then
  if diff <(tr -d '\r' <"$NEXORA_HUELLA_ESPERADA") "$work/huella.txt" >"$work/huella.diff"; then
    bien "igual a la huella de producción"
  else
    mal "distinta de la huella de producción (normal solo si hubo movimientos después de la instantánea):"
    sed 's/^/    /' "$work/huella.diff" >&2
  fi
else
  echo "  (Para compararla con producción: NEXORA_HUELLA_ESPERADA=<archivo>; ver huella.sql)"
fi

echo
if [[ "$fallas" -gt 0 ]]; then
  echo "✗ La prueba de restauración encontró ${fallas} problema(s)." >&2
  exit 1
fi
echo "✓ Respaldo restaurable y consistente (restauración en ${duracion} s)."
