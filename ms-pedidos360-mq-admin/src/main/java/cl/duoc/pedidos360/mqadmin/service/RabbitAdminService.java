package cl.duoc.pedidos360.mqadmin.service;

import java.util.List;

import cl.duoc.pedidos360.mqadmin.dto.BindingRequest;
import cl.duoc.pedidos360.mqadmin.dto.BindingResponse;
import cl.duoc.pedidos360.mqadmin.dto.CreateExchangeRequest;
import cl.duoc.pedidos360.mqadmin.dto.CreateQueueRequest;
import cl.duoc.pedidos360.mqadmin.dto.DeadLetterQueueStatus;
import cl.duoc.pedidos360.mqadmin.dto.ExchangeResponse;
import cl.duoc.pedidos360.mqadmin.dto.PurgeResponse;
import cl.duoc.pedidos360.mqadmin.dto.QueueResponse;
import cl.duoc.pedidos360.mqadmin.dto.ReplayResponse;

/**
 * Operaciones de alto nivel sobre la topología de RabbitMQ. Los controladores solo conocen esta
 * interfaz; los detalles de AMQP, RabbitAdmin y la API de management quedan en la implementación.
 *
 * <p>Errores de dominio: {@code ResourceNotFoundException} (no existe), {@code ResourceConflictException}
 * (ya existe, recurso protegido o precondición del broker), {@code InvalidAdminRequestException}
 * (configuración inválida según el estado del broker) y {@code BrokerUnavailableException}
 * (RabbitMQ o management no disponibles).
 */
public interface RabbitAdminService {

    List<QueueResponse> listQueues();

    QueueResponse getQueue(String name);

    QueueResponse createQueue(CreateQueueRequest request);

    void deleteQueue(String name, boolean ifUnused, boolean ifEmpty);

    PurgeResponse purgeQueue(String name);

    List<ExchangeResponse> listExchanges();

    ExchangeResponse createExchange(CreateExchangeRequest request);

    void deleteExchange(String name, boolean ifUnused);

    /** Lista bindings exchange -> cola; ambos filtros son opcionales ({@code null} = sin filtro). */
    List<BindingResponse> listBindings(String exchange, String queue);

    BindingResponse createBinding(BindingRequest request);

    void deleteBinding(String exchange, String queue, String routingKey);

    /** Profundidad, umbral y alerta de cada DLQ configurada. */
    List<DeadLetterQueueStatus> deadLetterQueues();

    /** Reenvía hasta {@code maxMessages} mensajes de una DLQ configurada a su exchange y routing key originales. */
    ReplayResponse replayDeadLetters(String queue, int maxMessages);
}
