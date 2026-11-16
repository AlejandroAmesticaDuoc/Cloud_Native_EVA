package cl.duoc.pedidos360.bff.controller;

import cl.duoc.pedidos360.bff.client.KafkaAdminClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/kafka-admin")
public class KafkaAdminController {

    private final KafkaAdminClient client;

    public KafkaAdminController(KafkaAdminClient client) {
        this.client = client;
    }

    @GetMapping("/topics")
    public Object listTopics() {
        return client.listTopics();
    }

    @PostMapping("/topics")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createTopic(@RequestBody Object body) {
        return client.createTopic(body);
    }

    @PatchMapping("/topics/{name}")
    public Object updateTopic(@PathVariable String name, @RequestBody Object body) {
        return client.updateTopic(name, body);
    }

    @DeleteMapping("/topics/{name}")
    public Object deleteTopic(@PathVariable String name) {
        return client.deleteTopic(name);
    }

    @GetMapping("/consumer-groups")
    public Object listConsumerGroups() {
        return client.listConsumerGroups();
    }

    @PostMapping("/messages")
    public Object publishMessage(@RequestBody Object body) {
        return client.publishMessage(body);
    }
}
