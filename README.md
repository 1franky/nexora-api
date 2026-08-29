# nexora-api

Backend/API central de **Nexora**, una plataforma de administración de finanzas personales inspirada en conceptos de herramientas como Firefly III.

Expone la información y las reglas de negocio que consumen [`nexora-web`](https://github.com/1franky/nexora-web) y [`nexora-android`](https://github.com/1franky/nexora-android).

```text
nexora-web        ──┐
                     ├──►  nexora-api  ──►  PostgreSQL
nexora-android    ──┘
```

## Funcionalidad

- Cuentas: débito/corriente, ahorro, tarjeta de crédito, AFORE, PPR (múltiples por usuario).
- Ingresos, egresos y transferencias entre cuentas.
- Compras con tarjeta de crédito, incluyendo MSI y MCI.
- Fechas de corte y fecha límite de pago por tarjeta.
- Notificaciones de pagos.
- Métricas y agregaciones para dashboards personalizables.
- Saldo disponible y patrimonio neto como métricas independientes y configurables por cuenta.

## Stack

- **Lenguaje**: Kotlin (JVM 21)
- **Framework**: Spring Boot 4 (Web, Data JPA, Security, Validation, Actuator)
- **Base de datos**: PostgreSQL 16
- **Migraciones**: Flyway
- **Documentación**: springdoc-openapi / Swagger UI
- **Build**: Gradle (Kotlin DSL)
- **Infraestructura**: Docker Compose (mismo `compose.yaml` en desarrollo y en el VPS)

## Arquitectura

Backend modular por bounded contexts, con posibilidad de evolucionar a microservicios cuando exista una razón técnica o de escalabilidad:

```text
identity
accounts
transactions
credit
notifications
dashboard
budgets
```

## API

Versionado bajo `/api/v1/`, documentada con OpenAPI. Web y Android consumen el mismo contrato.

Implementado hasta ahora (B2 — finanzas básicas, B3 — tarjetas de crédito, B4 — MSI/MCI, B5 — dashboard, B6 — notificaciones, B7 — auth JWT):

```text
POST /api/v1/auth/login     email + contraseña -> access token (JWT) + refresh token
POST /api/v1/auth/refresh   cambia un refresh token válido por un access token nuevo (rota el refresh token)
POST /api/v1/auth/logout    revoca un refresh token

POST /api/v1/users              registro
GET  /api/v1/users/me

POST /api/v1/accounts
GET  /api/v1/accounts
GET  /api/v1/accounts/{id}
GET  /api/v1/accounts/summary   dinero disponible / patrimonio neto

POST /api/v1/categories
GET  /api/v1/categories

POST /api/v1/transactions       INCOME / EXPENSE
GET  /api/v1/transactions?accountId=...

POST /api/v1/transfers          entre dos cuentas del mismo usuario

POST /api/v1/credit-cards
GET  /api/v1/credit-cards
GET  /api/v1/credit-cards/{id}             saldo, crédito disponible, próximo corte/pago
POST /api/v1/credit-cards/{id}/purchases   compra "de contado"
POST /api/v1/credit-cards/{id}/payments    pago desde otra cuenta del mismo usuario

POST /api/v1/credit-cards/{id}/installment-plans   compra a MSI/MCI
GET  /api/v1/credit-cards/{id}/installment-plans
GET  /api/v1/installment-plans/{id}                         cuotas + saldo financiado + próxima cuota
POST /api/v1/installment-plans/{id}/installments/{installmentId}/pay

GET  /api/v1/dashboard?month=yyyy-MM   patrimonio, disponible, ingresos/gastos del mes (+ por
                                        categoría), deuda y crédito disponible de tarjetas,
                                        próximos pagos, compromiso mensual MSI/MCI, evolución
                                        de patrimonio/gastos (6 meses) y últimos movimientos

GET  /api/v1/notifications?unreadOnly=false   genera al vuelo lo que falte y lista (más reciente primero)
POST /api/v1/notifications/{id}/read
POST /api/v1/notifications/read-all
```

## Seguridad

Autenticación con JWT propio: esta misma API emite y valida sus tokens (no hay un Authorization Server externo como Keycloak/Auth0 — Web y Android son clientes propios, de primera parte).

- **Access token**: JWT firmado HS256, corta duración (15 min por defecto). Se envía como `Authorization: Bearer <token>` en cada request.
- **Refresh token**: opaco (no es un JWT), 30 días por defecto. Solo se guarda su hash (SHA-256) en base de datos — igual que una contraseña. Se **rota** en cada uso: `POST /api/v1/auth/refresh` revoca el que se usó y entrega uno nuevo; reusar uno ya rotado (señal de robo) es rechazado.
- `POST /api/v1/users` (registro) y todo `/api/v1/auth/**` son los únicos endpoints públicos; el resto exige un access token válido y cada quien solo ve sus propios datos.
- **Rate limiting** (en memoria, por IP): 10 intentos/minuto en login y registro, para mitigar fuerza bruta. Solo válido para una instancia; si la API llega a escalar horizontalmente esto debería moverse a un store compartido (Redis).

`JWT_SECRET` (≥32 caracteres, ver `.env.example`) firma los tokens — cámbialo en producción, nunca uses el valor de ejemplo.

**CORS**: `nexora-web` es una SPA — las peticiones salen del navegador del usuario, un origen distinto al de esta API. `CORS_ALLOWED_ORIGINS` (por defecto `http://localhost:3006`) lista los orígenes permitidos, separados por comas; en el VPS debe incluir el dominio público real de `nexora-web`. Sin esto, el navegador bloquea toda petición cross-origin desde el preflight — curl/Postman nunca lo notan porque CORS es una restricción exclusiva del navegador.

RBAC más allá de un único rol (`ROLE_USER`), OAuth2/OpenID Connect con un proveedor externo, y auditoría completa (`created_by`, `audit_log`) quedan para si llegan a hacer falta (plan.md, secciones 11 y 15) — hoy no hay ningún endpoint que distinga roles. No se almacenan datos sensibles de tarjetas (CVV, NIP, número completo).

## Notificaciones

Se generan `PAYMENT_DUE` / `PAYMENT_DUE_SOON` (tarjeta con deuda cuyo pago vence hoy o en los próximos 3 días) e `INSTALLMENT_DUE` (cuota de un plan MSI/MCI por vencer). Se generan tanto al vuelo (cada `GET /api/v1/notifications`) como por un scheduler diario (`@Scheduled`, 8am), pensado para cuando se agregue push/email — hoy solo quedan disponibles vía esta API ("notificaciones web"). `PAYMENT_OVERDUE`, `BUDGET_EXCEEDED` y `UNUSUAL_EXPENSE` están declaradas pero no se disparan todavía (requieren seguimiento por ciclo/estado de cuenta, presupuestos, y detección de anomalías respectivamente — plan.md, sección 31).

## Exposición y red

- La API se expone siempre en el **puerto 3005** (host y contenedor), tanto en desarrollo como en el VPS.
- Postgres **no publica ningún puerto al host**: solo es alcanzable por el servicio `api` a través de la red interna de Docker `nexora-net` (ver [`compose.yaml`](./compose.yaml)). No es posible conectarse a la base de datos desde fuera de esa red (ni siquiera desde el propio host).

## Desarrollo local (macOS)

El desarrollo se hace únicamente en local, siempre contenerizado con Docker Compose (la API y Postgres corren como contenedores en la misma red interna); el despliegue final se hace aparte en el VPS con el mismo `compose.yaml` (ver sección siguiente).

Requisitos: Docker Desktop.

```bash
# 1. Copiar variables de entorno (una sola vez)
cp .env.example .env

# 2. Levantar API + Postgres (perfil "dev" por defecto)
docker compose up --build

# 3. Documentación interactiva de la API
open http://localhost:3005/swagger-ui.html

# 4. Health check
curl http://localhost:3005/actuator/health

# 5. Apagar
docker compose down
```

Los datos de Postgres persisten en un volumen Docker (`nexora-postgres-data`) entre reinicios.

Para compilar o correr las pruebas fuera de Docker (las pruebas usan Testcontainers, con su propio contenedor efímero, independiente de `compose.yaml`):

```bash
./gradlew build
./gradlew test
```

> Nota: `./gradlew bootRun` por sí solo ya **no** conecta a la base de datos — Postgres no tiene puerto publicado al host. El flujo de desarrollo es `docker compose up --build`.

## Despliegue (VPS)

Se usa el mismo [`compose.yaml`](./compose.yaml) que en desarrollo, con un `.env` propio del VPS (`SPRING_PROFILES_ACTIVE=prod` y credenciales reales, nunca las de dev):

```bash
docker compose up --build -d
```

La imagen de la API se construye con el [`Dockerfile`](./Dockerfile) (multi-stage, JDK 21 → JRE 21). Postgres sigue sin exponer ningún puerto al host/Internet, ni en el VPS.

## Estado del proyecto

En construcción. **B1 (base del proyecto)** a **B7 (calidad — autenticación JWT)** completos: login con JWT propio (access + refresh token rotado) y rate limiting, cuentas, categorías, ingresos/gastos, transferencias atómicas, tarjetas de crédito con ciclo de facturación (corte/pago), compras y pagos, compras a meses (con o sin intereses) con calendario de cuotas, un dashboard con las métricas del plan (patrimonio, disponible, gastos por categoría, deuda de tarjetas, próximos pagos, compromiso mensual MSI/MCI y evolución histórica), y notificaciones de pagos/cuotas por vencer (generadas al vuelo y por un scheduler diario). Ver [`plan.md`](./plan.md) para el plan de desarrollo completo (modelo de datos, roadmap, reglas arquitectónicas y MVP).

Pendiente de B7 (el resto de "Calidad"): auditoría, documentación OpenAPI más rica, revisión de performance — ver [issue #7](https://github.com/1franky/nexora-api/issues/7).

## Repositorios relacionados

- [`nexora-web`](https://github.com/1franky/nexora-web) — aplicación web
- [`nexora-android`](https://github.com/1franky/nexora-android) — aplicación Android
