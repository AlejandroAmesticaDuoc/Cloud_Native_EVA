package cl.duoc.pedidos360.kafkaadmin.controller;

import cl.duoc.pedidos360.kafkaadmin.dto.KafkaDtos.*;
import cl.duoc.pedidos360.kafkaadmin.service.KafkaAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/kafka-admin")
public class KafkaAdminController {

    private final KafkaAdminService service;

    public KafkaAdminController(KafkaAdminService service) {
        this.service = service;
    }

    @GetMapping("/topics")
    public List<TopicSummary> getTopics() {
        return service.listTopics();
    }

    @PostMapping("/topics")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationResponse createTopic(@Valid @RequestBody CreateTopicRequest request) {
        return service.createTopic(request);
    }

    @PatchMapping("/topics/{name}")
    public OperationResponse updateTopic(@PathVariable String name, @RequestBody UpdateTopicConfigRequest request) {
        return service.updateTopic(name, request);
    }

    @DeleteMapping("/topics/{name}")
    public OperationResponse deleteTopic(@PathVariable String name) {
        return service.deleteTopic(name);
    }

    @GetMapping("/consumer-groups")
    public List<ConsumerGroupSummary> getConsumerGroups() {
        return service.listConsumerGroups();
    }

    @PostMapping("/messages")
    public OperationResponse publishMessage(@Valid @RequestBody PublishMessageRequest request) {
        return service.publishMessage(request);
    }
}
