# Respaldos y restauración

**Qué se respalda:** la base de PostgreSQL, con `pg_dump` en formato custom. Es lo único que hace falta: la e.firma de cada usuario (cifrada) y los XML de los CFDI viven en la base, en columnas `BYTEA`. No hay volúmenes de archivos.

**Cómo:** con [restic](https://restic.net), cifrado antes de salir del VPS, hacia un bucket privado de Cloudflare R2 **propio de Nexora**. Tiene su bucket, su token y su contraseña, separados de los de Velora: si se filtra uno, el otro no queda expuesto. Corre todos los días a las **4:15**, hora del centro de México. Es después de la sincronización con el SAT (3:00) y del respaldo de Velora (3:30), que corre en el mismo VPS. Se conservan 7 respaldos diarios, 4 semanales y 6 mensuales. Los domingos, además, se lee una muestra del 5 % para detectar corrupción. Si algo falla, se abre un issue con la etiqueta `respaldo` en este repositorio. El repositorio es público, así que el issue solo dice el paso y el código de salida: nunca datos, rutas ni el nombre del servidor.

**Tope de tamaño:** `NEXORA_MAX_REPO_GB` (1 GB). Después de la retención se mide el repositorio con `restic stats --mode raw-data`. Al pasar el 80 % (`NEXORA_WARN_REPO_PCT`) y al pasar el tope se abre un issue con la etiqueta `respaldo`, sin repetirlo mientras siga abierto. **Solo avisa:**
- no detiene el respaldo, porque se perdería el RPO;
- no recorta la retención, porque podría borrar el respaldo bueno de antes de un error.

Al recibir el aviso, revisa por qué creció (`restic stats --mode raw-data`, `restic snapshots`). Después baja `NEXORA_KEEP_*` o sube el tope en `/etc/nexora/respaldo.env`, y cierra el issue.

- **RPO:** 24 horas. Se pierde a lo más lo registrado desde el último respaldo. Los CFDI se pueden volver a sincronizar desde el SAT.
- **RTO:** menos de 1 hora. Lo que más tarda es levantar el VPS; restaurar la base toma segundos.

## Lo que NO está en el respaldo y hace falta para restaurar

Guárdalo en tu gestor de contraseñas. Sin estos datos, el respaldo no sirve o sirve a medias:

| Secreto | Dónde vive | Si se pierde |
|---|---|---|
| Contraseña de restic | `/etc/nexora/restic-password` | **Los respaldos no se pueden descifrar.** |
| `NEXORA_SAT_ENCRYPTION_KEY` | `.env` de la API | La e.firma de la base queda cifrada para siempre. Cada usuario tiene que volver a subirla. |
| `JWT_SECRET` | `.env` de la API | Solo se cierran las sesiones; con uno nuevo basta. |
| Resto del `.env` y `respaldo.env` | VPS | Se regeneran (tokens de R2, de alertas, de Resend). |

## Instalación en el VPS (una vez)

1. **Cloudflare R2:**
   1. Crea el bucket privado `nexora-respaldos`.
   2. Crea un token de API de R2 con permiso **Object Read & Write solo sobre ese bucket**.
   3. Anota el Access Key ID, el Secret Access Key y el ID de tu cuenta.
2. **Token de alertas:** un token fine-grained de GitHub con **Issues: Read and write** solo en `1franky/nexora-api`. En el repositorio, crea la etiqueta `respaldo`.
3. **restic** (0.17 o posterior, por `--stdin-from-command` y los códigos de salida). Ya está instalado si el VPS tiene los respaldos de Velora:

   ```bash
   restic version     # si falta o es menor a 0.17: sudo apt install restic && sudo restic self-update
   ```

   El binario debe quedar en `/usr/bin` o `/usr/local/bin`. systemd no busca en `~/.local/bin`.

4. **Secretos:**

   ```bash
   sudo mkdir -p /etc/nexora
   sudo cp ops/respaldos/respaldo.env.example /etc/nexora/respaldo.env   # y llénalo
   openssl rand -base64 32 | sudo tee /etc/nexora/restic-password >/dev/null
   sudo chown root:francisco /etc/nexora/respaldo.env /etc/nexora/restic-password
   sudo chmod 640 /etc/nexora/respaldo.env /etc/nexora/restic-password
   ```

   **Guarda en tu gestor de contraseñas la contraseña de `/etc/nexora/restic-password` y el `.env` de la API** (ver la tabla de arriba).

5. **Servicio y temporizador:**

   ```bash
   sudo cp ops/respaldos/nexora-respaldo.{service,timer} /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now nexora-respaldo.timer
   sudo systemctl start nexora-respaldo.service      # primer respaldo, ahora (crea el repositorio)
   journalctl -u nexora-respaldo -n 50               # debe terminar en "Respaldo completo"
   ```

## Consultar

```bash
set -a; source /etc/nexora/respaldo.env; set +a
restic snapshots                  # instantáneas, con sus etiquetas nexora y db
systemctl list-timers nexora-respaldo.timer
```

## Restaurar

En un VPS nuevo o tras perder la base, desde `/home/francisco/docker/nexora/nexora-api`:

1. Instala restic. Copia `/etc/nexora/respaldo.env` y `restic-password` (paso 4), y el `.env` de la API **con la misma `NEXORA_SAT_ENCRYPTION_KEY`**, desde tu gestor de contraseñas.
2. Levanta **solo** PostgreSQL, con la base vacía, y deja la API apagada. Si la API arranca antes, Flyway crea un esquema vacío y `restaurar.sh` se niega a escribir encima.

   ```bash
   docker compose up -d postgres
   ```

   Si el VPS sigue vivo y la base está dañada, no borres el volumen sin más. Primero apártalo:

   ```bash
   docker compose stop api
   vol=$(docker volume ls -q | grep 'nexora-postgres-data$')      # el nombre real lleva el prefijo del proyecto
   docker volume create "${vol}-danado"
   docker compose stop postgres
   docker run --rm -v "$vol:/from:ro" -v "${vol}-danado:/to" alpine:3.22 sh -c 'cp -a /from/. /to/'
   docker compose rm -f postgres && docker volume rm "$vol"
   docker compose up -d postgres
   ```

3. Restaura la última instantánea (o una en particular, con su ID de `restic snapshots`):

   ```bash
   set -a; source /etc/nexora/respaldo.env; set +a
   ops/respaldos/restaurar.sh                     # o: ops/respaldos/restaurar.sh <id>
   ```

4. Levanta la API, que aplica con Flyway las migraciones pendientes, y comprueba:

   ```bash
   docker compose up -d
   curl -s http://127.0.0.1:3005/actuator/health    # {"status":"UP"}
   ```

   Entra a la web y revisa el dashboard, los movimientos de una cuenta y la pantalla del SAT, que debe mostrar la e.firma conectada sin pedir que la vuelvas a subir.

## Prueba

`ops/respaldos/probar.sh` crea una base con el **esquema real**: aplica las migraciones de Flyway del repositorio, con la imagen de PostgreSQL de `compose.yaml`. Le mete datos de prueba con e.firma y XML de CFDI en `BYTEA`, la respalda en un repositorio restic local, la restaura en una base nueva y compara la huella (`huella.sql`). También comprueba:
- que el aviso de tamaño no se repita;
- que una contraseña equivocada falle y avise sin crear un repositorio nuevo;
- que no se restaure encima de una base con esquema;
- que un respaldo sin base de datos falle y avise sin guardar una instantánea;
- que `probar-restauracion.sh` pase contra ese repositorio.

El CI la corre en cada PR y en cada push a `develop` y `main`. En local requiere Docker y restic. Si una migración cambia una tabla que usa `huella.sql`, esta prueba falla y obliga a actualizar la huella.

**Prueba de restauración real:** cada tres meses, en una máquina aparte. Es lo único que demuestra que el bucket tiene datos sanos. `probar-restauracion.sh` hace todo de una vez:

1. Lee el **100 %** del repositorio (`restic check --read-data`).
2. Restaura la última instantánea, o la que le pases, en una base desechable, con `restaurar.sh`.
3. Comprueba:
   - que Flyway no tenga migraciones fallidas y que todas las restricciones estén validadas;
   - que haya usuarios;
   - que cada e.firma tenga sus cuatro partes;
   - que cada CFDI tenga su XML.
4. Saca la huella de los datos (`huella.sql`: conteos y md5, sin datos personales ni importes) y la compara con la de producción.
5. Borra la base al terminar, también si algo falla.

El script solo lee el repositorio: nunca respalda ni hace `forget`. Como `restic check` toma un lock exclusivo, no lo corras cerca de las 4:15.

```bash
# En el VPS, justo después del respaldo y antes de que alguien registre algo: huella de producción (solo lectura)
docker exec -i nexora-postgres psql -U nexora -d nexora -tA < ops/respaldos/huella.sql > huella-produccion.txt

# En la otra máquina, con Docker y restic, desde nexora-api/. El respaldo.env es una copia del del VPS, con
# RESTIC_PASSWORD_FILE apuntando a una copia local de la contraseña. Bórralos al terminar.
set -a; source /ruta/a/respaldo.env; set +a
NEXORA_HUELLA_ESPERADA=huella-produccion.txt ops/respaldos/probar-restauracion.sh
```

Termina en `✓ Respaldo restaurable y consistente` o con la lista de problemas y código 1. La huella solo difiere si hubo movimientos después de la instantánea (incluidos los cargos programados de las 0:05). Opciones:
- `NEXORA_CHECK_SUBSET=10` lee solo el 10 % del repositorio;
- `NEXORA_CONSERVAR=1` deja la base para revisarla a mano.

### Registro de pruebas de restauración

| Fecha | Instantánea | Dónde | Resultado |
|---|---|---|---|
| 2026-11-18 | `05c782ee` (primer respaldo) | VPS, misma máquina (no cuenta como la trimestral) | ✓ `restic check` al 10 % (1 de 1 pack), restaurada en 3 s, Flyway v14, huella igual a la de producción |
| — | — | — | Pendiente: primera prueba trimestral en una máquina aparte |
