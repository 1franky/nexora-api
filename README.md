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
- **Infraestructura**: Docker Compose (desarrollo), Docker (despliegue en el VPS)

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

## Desarrollo local (macOS)

El desarrollo se hace únicamente en local; el despliegue final se hace aparte en el VPS (ver sección siguiente).

Requisitos: JDK 21 (usa el toolchain de Gradle si no está instalado), Docker Desktop.

```bash
# 1. Levanta Postgres y arranca la app (perfil "dev" por defecto).
#    Spring Boot Docker Compose levanta compose.yaml automáticamente.
./gradlew bootRun

# 2. Ejecutar pruebas (usan Testcontainers, requiere Docker corriendo)
./gradlew test

# 3. Documentación interactiva de la API
open http://localhost:8080/swagger-ui.html

# 4. Health check
curl http://localhost:8080/actuator/health
```

La base de datos local vive en el contenedor `nexora-postgres` (definido en [`compose.yaml`](./compose.yaml)), con datos persistidos en un volumen Docker.

## Despliegue (VPS)

La imagen de producción se construye con el [`Dockerfile`](./Dockerfile) (multi-stage, JDK 21 → JRE 21). En el VPS se ejecuta con el perfil `prod` y las variables de [`.env.example`](./.env.example) (copiar a `.env`, nunca versionarlo):

```bash
docker build -t nexora-api .
docker run --env-file .env -p 8080:8080 nexora-api
```

`SPRING_PROFILES_ACTIVE=prod` desactiva Docker Compose Support y toma el datasource de `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` (Postgres del VPS, fuera de este repo).

## Estado del proyecto

En construcción — fase **B1 (base del proyecto)** del roadmap: proyecto Kotlin/Spring Boot, PostgreSQL, Docker Compose, Flyway, configuración por ambientes, seguridad base y OpenAPI ya en pie; CI básico en GitHub Actions. Ver [`plan.md`](./plan.md) para el plan de desarrollo completo (modelo de datos, roadmap, reglas arquitectónicas y MVP).

## Repositorios relacionados

- [`nexora-web`](https://github.com/1franky/nexora-web) — aplicación web
- [`nexora-android`](https://github.com/1franky/nexora-android) — aplicación Android
