-- B11: agrega SAT_SYNC_COMPLETED/SAT_SYNC_FAILED al CHECK de notifications.type
-- (V5__b6_notificaciones.sql) — SatSyncService las genera al terminar (o
-- fallar) una sincronización.

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;

ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
    'PAYMENT_DUE', 'PAYMENT_DUE_SOON', 'PAYMENT_OVERDUE',
    'INSTALLMENT_DUE', 'BUDGET_EXCEEDED', 'UNUSUAL_EXPENSE',
    'SAT_SYNC_COMPLETED', 'SAT_SYNC_FAILED'
));
