# Plan de desarrollo — nexora-api

## 1. Visión general

`nexora-api` es el backend/API central de **Nexora**, una plataforma de administración de finanzas personales inspirada en conceptos de herramientas como Firefly III. Expone la información y las reglas de negocio que consumirán `nexora-web` y `nexora-android`.

Funcionalidad que debe soportar:

- Cuentas de ahorro.
- Cuentas de débito/corriente.
- Tarjetas de crédito.
- Ahorro/información de AFORE.
- PPR (Plan Personal de Retiro), con soporte para más de un PPR por usuario.
- Registro de ingresos y egresos.
- Transferencias entre cuentas.
- Compras con tarjeta de crédito.
- Compras a MSI y MCI.
- Fechas de corte y fechas límite de pago.
- Notificaciones de pagos de tarjetas.
- Métricas para dashboards personalizables (consumidas por Web y Android).

Este repositorio es la pieza central del ecosistema:

```text
nexora-web        ──┐
                     ├──►  nexora-api  ──►  PostgreSQL
nexora-android    ──┘
```

---

## 2. Principios funcionales

### 2.1 Tipos de cuentas

- Cuenta de débito/corriente.
- Cuenta de ahorro.
- Tarjeta de crédito.
- AFORE.
- PPR (Plan Personal de Retiro).

Cada cuenta tendrá propiedades específicas según su tipo.

#### PPR (Plan Personal de Retiro)

Debe permitirse registrar **más de un PPR por usuario**. Cada PPR es una cuenta independiente, con su propia institución, saldo, moneda y configuración de inclusión en métricas.

```text
User 1 ──────── N PPR
```

Por defecto los PPR se consideran activos de largo plazo: se incluyen en el patrimonio neto, pero no necesariamente en el saldo disponible. Configurable por el usuario.

### 2.2 Configuración de inclusión en saldos

Cada cuenta podrá configurarse para incluirse o excluirse de:

- Saldo disponible.
- Patrimonio neto.

### 2.3 Métricas calculadas

**Dinero disponible**: dinero que el usuario considera utilizable actualmente.

**Patrimonio neto**:

```text
Activos - Pasivos
```

---

## 3. Movimientos financieros

Tipos de movimiento (no limitarse a `INCOME`/`EXPENSE`):

```text
INCOME
EXPENSE
TRANSFER
CREDIT_CARD_PURCHASE
CREDIT_CARD_PAYMENT
REFUND
ADJUSTMENT
```

- Las **transferencias** son una operación independiente, nunca ingreso + gasto.
- El **pago de tarjeta** es una operación independiente, nunca un gasto adicional (el gasto ya ocurrió en la compra).

---

## 4. Tarjetas de crédito

Cada tarjeta almacena como mínimo: nombre, banco, últimos 4 dígitos, límite de crédito, día de corte, día límite de pago, moneda, estado, fecha de alta.

El sistema calcula automáticamente:

- Saldo actual.
- Crédito disponible.
- Próxima fecha de corte.
- Próxima fecha límite de pago.
- Pago esperado.
- Deuda pendiente.
- Cuotas futuras.

### Ciclo de facturación

El backend es responsable de determinar correctamente a qué ciclo pertenece cada compra según el día de corte de la tarjeta.

---

## 5. Compras con tarjeta, MSI y MCI

Modelo conceptual de compra:

```text
Compra
  |
  +-- Tarjeta
  +-- Comercio
  +-- Categoría
  +-- Fecha
  +-- Importe original
  +-- Tipo de compra
  +-- Plan MSI/MCI
```

### MSI (meses sin intereses)

Cuotas iguales, interés 0%.

### MCI (meses con intereses)

Debe almacenarse: monto original, número de cuotas, tasa de interés, interés total, monto total, monto de cuota, fecha de inicio. **No** almacenar únicamente el pago mensual (se necesitan reportes y saldos).

```text
Compra original
      |
      v
InstallmentPlan
      |
      +-- Installment 1..N
```

Debe permitir conocer: cuotas pagadas/pendientes, saldo financiado, próxima cuota, monto mensual comprometido, fecha de finalización.

---

## 6. Pagos de tarjetas

Operación específica `CREDIT_CARD_PAYMENT`, soportando pago total, parcial o de cantidad específica.

```text
Cuenta bancaria ── pago ──► Tarjeta de crédito
```

---

## 7. Notificaciones

Eventos propuestos:

```text
PAYMENT_DUE
PAYMENT_DUE_SOON
PAYMENT_OVERDUE
INSTALLMENT_DUE
BUDGET_EXCEEDED
UNUSUAL_EXPENSE
```

Ejemplo: *"Tu tarjeta BBVA vence en 3 días. Pago estimado: $4,850."*

Canales previstos a futuro: web, email, push (Android vía Firebase Cloud Messaging — el backend deberá exponer lo necesario para disparar esos eventos).

---

## 8. Métricas para dashboard

El backend debe exponer las agregaciones necesarias para que Web y Android construyan dashboards personalizables:

- Patrimonio neto / Dinero disponible.
- Ingresos y gastos del mes / por categoría.
- Balance mensual.
- Deuda de tarjetas / Crédito disponible.
- Próximos pagos.
- MSI/MCI activos y monto mensual comprometido.
- Evolución del patrimonio / de gastos.
- Ahorro mensual, metas de ahorro.
- AFORE.
- Últimas transacciones.

---

## 9. Stack propuesto

- **Lenguaje**: Kotlin.
- **Framework**: Spring Boot.
- **Componentes**: Spring Web, Spring Data JPA, Spring Security, Spring Validation, Spring Actuator, OpenAPI/Swagger, Flyway o Liquibase.
- **Base de datos**: PostgreSQL.
- **Infraestructura**: Docker, Docker Compose (desarrollo), HTTPS en producción.

---

## 10. Arquitectura

Diseño conceptual por bounded contexts, comenzando con un **backend modular** (evitar microservicios físicos prematuros).

Módulos iniciales:

```text
identity
accounts
transactions
credit
notifications
dashboard
budgets
```

```text
                   ┌─────────────────┐
                   │ Android / Web   │
                   └────────┬────────┘
                            v
                    ┌──────────────┐
                    │ API Gateway  │
                    └──────┬───────┘
                           │
          ┌────────────────┼────────────────┐
          v                v                v
     Accounts        Transactions      Credit Cards
          │                │                │
          └────────────────┼────────────────┘
                           v
                       PostgreSQL
                           │
                    ┌──────┴──────┐
                    v             v
              Notifications   Scheduler
```

Evolución futura hacia microservicios (solo si hay razón técnica/escalabilidad):

```text
identity-service
account-service
transaction-service
credit-service
notification-service
dashboard-service
```

---

## 11. Seguridad

- OAuth2/OpenID Connect.
- JWT + Refresh Tokens.
- RBAC.
- HTTPS.
- Validación de entrada.
- Rate limiting cuando sea necesario.
- Auditoría.

**Nunca almacenar**: CVV, NIP, contraseñas bancarias, número completo de tarjeta.

Identificación de tarjetas mediante datos no sensibles, ej. `BBVA **** **** **** 1234`.

---

## 12. Modelo de datos inicial

Entidades principales:

```text
User
Account
Category
Transaction
CreditCard
InstallmentPlan
Installment
Payment
Notification
Budget
SavingGoal
Dashboard
AuditLog
```

### Account

```text
id, user_id, name, type, currency, balance,
include_in_available_balance, include_in_net_worth,
status, created_at, updated_at
```

### Transaction

```text
id, account_id, type, amount, date, description,
category_id, status, created_at, updated_at
```

### CreditCard

```text
id, account_id, name, credit_limit, closing_day,
payment_due_day, status
```

### InstallmentPlan

```text
id, credit_card_id, transaction_id, original_amount,
installment_count, interest_rate, interest_amount,
total_amount, installment_amount, start_date, end_date, status
```

---

## 13. Auditoría

Trazabilidad obligatoria por tratarse de información financiera.

Eventos: `TransactionCreated/Updated/Deleted`, `CreditCardPurchaseCreated`, `InstallmentPlanCreated`, `InstallmentCreated`, `PaymentCreated`.

Registrar `created_at`, `updated_at`, `created_by`, y `audit_log` cuando sea necesario.

---

## 14. API REST

Versionado obligatorio: `/api/v1/`

```text
/api/v1/auth
/api/v1/users
/api/v1/accounts
/api/v1/categories
/api/v1/transactions
/api/v1/transfers
/api/v1/credit-cards
/api/v1/credit-cards/{id}/purchases
/api/v1/installment-plans
/api/v1/installments
/api/v1/payments
/api/v1/notifications
/api/v1/dashboard
/api/v1/budgets
/api/v1/saving-goals
```

Documentación mediante OpenAPI. Diseñar para que Web y Android compartan exactamente el mismo contrato.

---

## 15. Moneda

Mercado inicial: México (MXN), pero el modelo debe soportar múltiples monedas (`MXN`, `USD`, `EUR`, ...). Cada cuenta tiene una moneda propia.

---

## 16. Consideraciones para soporte offline (Android)

La API debe diseñarse pensando en la futura sincronización offline de Android:

- Idempotencia.
- Identificadores únicos.
- Sincronización.
- Manejo de conflictos.

---

## 17. Roadmap

### B1 — Base del proyecto

- Crear proyecto Kotlin.
- Configurar Spring Boot.
- PostgreSQL.
- Docker.
- Migraciones.
- Configuración por ambientes.
- Seguridad.
- OpenAPI.
- CI/CD básico.

### B2 — Finanzas básicas

- Usuarios.
- Cuentas.
- Categorías.
- Ingresos.
- Gastos.
- Transferencias.
- Cálculo de saldos.

### B3 — Tarjetas

- Crear tarjetas.
- Límite, corte, fecha límite.
- Compras.
- Pagos.
- Crédito disponible.

### B4 — MSI/MCI

- Crear planes.
- Generar cuotas.
- Calcular intereses.
- Estados de cuotas.
- Calendario de pagos.
- Saldo financiado.

### B5 — Dashboard

- Métricas.
- Agregaciones.
- Reportes.
- Evolución histórica.

### B6 — Notificaciones

- Scheduler.
- Recordatorios.
- Eventos de pago.
- Notificaciones push.
- Preparación para email.

### B7 — Calidad

- Unit tests.
- Integration tests.
- Contract tests.
- Seguridad.
- Auditoría.
- Performance.
- Documentación.

---

## 18. MVP del backend

- Cuentas: débito, ahorro, crédito, AFORE.
- Movimientos: ingreso, gasto, transferencia.
- Tarjetas: límite, corte, fecha de pago, compra, pago.
- MSI/MCI: compra a MSI/MCI, calendario de cuotas, saldo pendiente.
- Dashboard: disponible, patrimonio, ingresos, gastos, deuda, próximos pagos.

---

## 19. Funcionalidades posteriores al MVP

```text
Presupuestos
Metas de ahorro
Importación CSV
Importación de estados de cuenta
Reportes avanzados
Gráficas
Histórico de AFORE
Inversiones
CETES
Patrimonio histórico
Multi-moneda avanzada
Transacciones recurrentes
Detección de gastos recurrentes
Exportación
Backups
2FA
Integraciones bancarias
```

---

## 20. Reglas arquitectónicas importantes

1. El dominio financiero debe ser independiente de la interfaz.
2. Web y Android no deben implementar reglas financieras duplicadas: la lógica vive aquí.
3. Los cálculos importantes deben realizarse en backend.
4. Las transferencias no deben contabilizarse como ingresos y gastos.
5. El pago de una tarjeta no debe generar un gasto adicional.
6. Las compras MSI/MCI deben conservar relación con su compra original.
7. El saldo disponible y patrimonio neto deben ser métricas distintas.
8. Las cuentas deben poder configurarse para incluirse/excluirse de determinadas métricas.
9. No se deben almacenar datos sensibles de tarjetas.
10. La API debe ser versionada.
11. La API debe documentarse con OpenAPI.
12. Deben existir pruebas automatizadas para los cálculos financieros.
13. Web y Android deben compartir el mismo contrato de API.

---

## 21. Repositorios relacionados

- `nexora-web` — aplicación web (consume esta API).
- `nexora-android` — aplicación Android (consume esta API).
