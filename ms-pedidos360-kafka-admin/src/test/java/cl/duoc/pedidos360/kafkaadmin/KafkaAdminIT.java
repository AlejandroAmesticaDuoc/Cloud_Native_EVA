package cl.duoc.pedidos360.kafkaadmin;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import cl.duoc.pedidos360.kafkaadmin.dto.CreateTopicRequest;
import cl.duoc.pedidos360.kafkaadmin.dto.OffsetResetStrategy;
import cl.duoc.pedidos360.kafkaadmin.dto.ResetOffsetsRequest;
import cl.duoc.pedidos360.kafkaadmin.dto.UpdateTopicConfigsRequest;
import cl.duoc.pedidos360.kafkaadmin.exception.InvalidAdminRequestException;
import cl.duoc.pedidos360.kafkaadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.kafkaadmin.exception.ResourceNotFoundException;
import cl.duoc.pedidos360.kafkaadmin.service.KafkaAdminService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Administración real contra un broker Kafka 3.9 (misma versión que los brokers Confluent 7.9 del compose). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KafkaAdminIT {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Autowired
    KafkaAdminService service;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void managesTopicsAndTheirConfigurationAgainstARealBroker() throws Exception {
        assertEquals(1, service.cluster().brokers().size());
        var created = service.createTopic(new CreateTopicRequest("it.demo", 3, 1,
                Map.of("retention.ms", "3600000", "cleanup.policy", "delete")));
        assertEquals(3, created.partitions().size());
        assertEquals("3600000", created.configs().get("retention.ms"));
        assertThrows(ResourceConflictException.class,
                () -> service.createTopic(new CreateTopicRequest("it.demo", 3, 1, Map.of())));
        assertThrows(InvalidAdminRequestException.class,
                () -> service.createTopic(new CreateTopicRequest("it.replicas", 1, 2, Map.of())), "RF mayor que los brokers");

        var updated = service.updateTopicConfigs("it.demo", new UpdateTopicConfigsRequest(
                Map.of("retention.ms", "7200000", "cleanup.policy", "compact,delete"), Set.of()));
        assertEquals("7200000", updated.configs().get("retention.ms"));
        assertEquals("compact,delete", updated.configs().get("cleanup.policy"));
        var reset = service.updateTopicConfigs("it.demo", new UpdateTopicConfigsRequest(Map.of(), Set.of("retention.ms")));
        assertEquals("604800000", reset.configs().get("retention.ms"), "vuelve al valor por defecto del broker");

        assertEquals(4, service.increasePartitions("it.demo", 4).partitions().size());
        assertThrows(InvalidAdminRequestException.class, () -> service.increasePartitions("it.demo", 2));
        assertTrue(service.listTopics(false).stream().anyMatch(topic -> topic.name().equals("it.demo")));
        assertTrue(service.listTopics(true).size() >= service.listTopics(false).size(), "includeInternal agrega los internos");

        mvc.perform(get("/api/v1/kafka-admin/topics/it.demo").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_pedidos360.access"), new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.partitions.length()").value(4));

        service.deleteTopic("it.demo");
        waitUntil(() -> service.listTopics(false).stream().noneMatch(topic -> topic.name().equals("it.demo")));
        assertThrows(ResourceNotFoundException.class, () -> service.getTopic("it.demo"));
        assertThrows(ResourceConflictException.class, () -> service.deleteTopic("orders.events"));
    }

    @Test
    void inspectsLagResetsOffsetsAndDeletesAConsumerGroup() throws Exception {
        service.createTopic(new CreateTopicRequest("it.lag", 1, 1, Map.of()));
        produce("it.lag", 5);
        try (var consumer = consumer("it-group")) {
            consumer.subscribe(List.of("it.lag"));
            int read = 0;
            long deadline = System.currentTimeMillis() + 20000;
            while (read < 5 && System.currentTimeMillis() < deadline) read += consumer.poll(Duration.ofMillis(500)).count();
            assertEquals(5, read);
            consumer.commitSync();
        }
        produce("it.lag", 2);

        var group = service.getConsumerGroup("it-group");
        assertEquals(2, group.totalLag(), "2 mensajes nuevos sin consumir");
        assertEquals(5, group.offsets().getFirst().committedOffset());
        assertEquals(7, group.offsets().getFirst().endOffset());
        assertTrue(service.listConsumerGroups().stream().anyMatch(summary -> summary.groupId().equals("it-group")));

        var reset = service.resetOffsets("it-group", new ResetOffsetsRequest("it.lag", OffsetResetStrategy.EARLIEST, null));
        assertEquals(0, reset.offsets().getFirst().offset());
        assertEquals(7, service.getConsumerGroup("it-group").totalLag());
        service.resetOffsets("it-group", new ResetOffsetsRequest("it.lag", OffsetResetStrategy.LATEST, null));
        assertEquals(0, service.getConsumerGroup("it-group").totalLag());

        service.deleteConsumerGroup("it-group");
        assertThrows(ResourceNotFoundException.class, () -> service.getConsumerGroup("it-group"));
        service.deleteTopic("it.lag");
    }

    private static void produce(String topic, int count) throws Exception {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(properties)) {
            for (int i = 0; i < count; i++) producer.send(new ProducerRecord<>(topic, "k" + i, "{\"n\":" + i + "}")).get();
        }
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("La condición no se cumplió a tiempo");
            Thread.sleep(250);
        }
    }
}
