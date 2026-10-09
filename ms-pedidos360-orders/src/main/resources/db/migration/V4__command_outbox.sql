-- La outbox de avisos pasa a guardar los cuatro tipos de comando RabbitMQ (correo, correo prioritario,
-- ticket de cocina y boleta). Las filas existentes eran correos.
ALTER TABLE notification_outbox ADD COLUMN command_type VARCHAR(30) NOT NULL DEFAULT 'EMAIL';
ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_command_type
    CHECK (command_type IN ('EMAIL', 'EMAIL_PRIORITY', 'KITCHEN_TICKET', 'INVOICE'));

-- Fecha en que se agotaron los intentos de publicación; esas filas ya no se reintentan.
ALTER TABLE notification_outbox ADD COLUMN failed_at TIMESTAMP WITH TIME ZONE NULL;

-- Ticket y boleta incluyen hasta 50 líneas.
ALTER TABLE notification_outbox ALTER COLUMN payload SET DATA TYPE VARCHAR(16000);
