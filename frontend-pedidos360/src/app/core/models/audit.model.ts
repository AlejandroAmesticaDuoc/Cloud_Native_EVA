export interface AuditEventPayload {
  schemaVersion?: number;
  eventId: string;
  eventType: string;
  orderId: number;
  aggregateVersion?: number;
  occurredAt: string;
  traceId?: string;
  actorId?: string;
  customerId?: string;
  previousStatus?: string | null;
  status?: string;
  createdAt?: string;
  total?: number;
}

export interface AuditRecord {
  id: number;
  recordedAt: string;
  event: AuditEventPayload;
}

export interface AuditPage {
  items: AuditRecord[];
  nextAfterId: number | null;
}
