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

- **Lenguaje**: Kotlin
- **Framework**: Spring Boot (Web, Data JPA, Security, Validation, Actuator)
- **Base de datos**: PostgreSQL
- **Migraciones**: Flyway o Liquibase
- **Documentación**: OpenAPI / Swagger
- **Infraestructura**: Docker, Docker Compose (desarrollo)

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

## Estado del proyecto

En fase de diseño / arranque. Ver [`plan.md`](./plan.md) para el plan de desarrollo completo (modelo de datos, roadmap, reglas arquitectónicas y MVP).

## Repositorios relacionados

- [`nexora-web`](https://github.com/1franky/nexora-web) — aplicación web
- [`nexora-android`](https://github.com/1franky/nexora-android) — aplicación Android
