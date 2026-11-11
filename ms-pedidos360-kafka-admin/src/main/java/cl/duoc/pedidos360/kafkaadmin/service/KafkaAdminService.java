package cl.duoc.pedidos360.kafkaadmin.service;

import java.util.List;
import cl.duoc.pedidos360.kafkaadmin.dto.*;

/**
 * Operaciones de alto nivel sobre el clúster Kafka. Los controladores solo conocen esta interfaz; el cliente
 * Admin de Kafka, sus futuros y sus excepciones quedan en la implementación.
 *
 * <p>Errores de dominio: {@code ResourceNotFoundException} (tópico o grupo inexistente), {@code ResourceConflictException}
 * (ya existe, recurso protegido o grupo con consumidores activos), {@code InvalidAdminRequestException}
 * (configuración rechazada por el clúster) y {@code BrokerUnavailableException} (Kafka no responde).
 */
public interface KafkaAdminService {

    ClusterResponse cluster();

    List<TopicSummary> listTopics(boolean includeInternal);

    TopicDetail getTopic(String name);

    TopicDetail createTopic(CreateTopicRequest request);

    /** Cambia configuraciones de forma incremental (set y reset); el resto del tópico no se modifica. */
    TopicDetail updateTopicConfigs(String name, UpdateTopicConfigsRequest request);

    /** Aumenta el total de particiones; no se permite en tópicos base ni para reducir. */
    TopicDetail increasePartitions(String name, int totalPartitions);

    void deleteTopic(String name);

    List<ConsumerGroupSummary> listConsumerGroups();

    /** Miembros, asignación y lag por partición del grupo. */
    ConsumerGroupDetail getConsumerGroup(String groupId);

    /** Reinicia los offsets del grupo en un tópico; exige que el grupo no tenga consumidores activos. */
    ResetOffsetsResponse resetOffsets(String groupId, ResetOffsetsRequest request);

    void deleteConsumerGroup(String groupId);
}
