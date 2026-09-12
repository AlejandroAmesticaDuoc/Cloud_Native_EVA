package cl.duoc.pedidos360.bff.service;

import cl.duoc.pedidos360.bff.client.AuditClient;
import cl.duoc.pedidos360.bff.dto.audit.AuditPage;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditClient client;

    public AuditService(AuditClient client) {
        this.client = client;
    }

    public AuditPage find(Long orderId, long afterId, int size) {
        if (afterId < 0 || size < 1 || size > 100 || (orderId != null && orderId < 1)) {
            throw new IllegalArgumentException("Consulta de auditoría inválida");
        }
        return client.find(orderId, afterId, size);
    }
}
