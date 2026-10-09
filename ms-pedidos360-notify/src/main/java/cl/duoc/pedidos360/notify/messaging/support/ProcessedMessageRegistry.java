package cl.duoc.pedidos360.notify.messaging.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Registro en memoria, acotado (LRU) y seguro entre hilos de los eventIds ya procesados.
 * Notify no tiene base de datos: evita reenviar un correo ante reentregas del broker mientras el proceso viva.
 * Un eventId se marca solo después de que el correo fue aceptado por SMTP.
 */
public class ProcessedMessageRegistry {
    private final Map<UUID, Boolean> processed;

    public ProcessedMessageRegistry(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("La capacidad debe ser mayor que 0");
        this.processed = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > capacity;
            }
        });
    }

    /** Consulta y refresca la posición LRU del eventId. */
    public boolean isProcessed(UUID eventId) {
        return processed.get(eventId) != null;
    }

    public void markProcessed(UUID eventId) {
        processed.put(eventId, Boolean.TRUE);
    }

    public int size() {
        return processed.size();
    }
}
