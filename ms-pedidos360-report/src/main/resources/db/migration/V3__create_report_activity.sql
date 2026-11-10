-- Proyección de audit.timeline para las métricas de actividad en tiempo real (acciones por tipo, actor y minuto).
CREATE TABLE report_activity (
    entry_id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL UNIQUE,
    order_id BIGINT NOT NULL CHECK (order_id > 0),
    actor_id VARCHAR(200) NOT NULL,
    action VARCHAR(30) NOT NULL CHECK (action IN ('OrderCreated', 'OrderAccepted', 'OrderStatusChanged', 'OrderCancelled')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('CREADO', 'ACEPTADO', 'EN_PREPARACION', 'DESPACHADO', 'ENTREGADO', 'CANCELADO')),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trace_id VARCHAR(100) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_report_activity_occurred ON report_activity(occurred_at);
