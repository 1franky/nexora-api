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

## Seguridad

OAuth2/OpenID Connect, JWT + Refresh Tokens, RBAC, HTTPS, auditoría. No se almacenan datos sensibles de tarjetas (CVV, NIP, número completo).

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

En construcción — fase **B1 (base del proyecto)** del roadmap: proyecto Kotlin/Spring Boot, PostgreSQL, Docker Compose, Flyway, configuración por ambientes, seguridad base y OpenAPI ya en pie; CI básico en GitHub Actions. Ver [`plan.md`](./plan.md) para el plan de desarrollo completo (modelo de datos, roadmap, reglas arquitectónicas y MVP).

## Repositorios relacionados

- [`nexora-web`](https://github.com/1franky/nexora-web) — aplicación web
- [`nexora-android`](https://github.com/1franky/nexora-android) — aplicación Android
