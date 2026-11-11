package cl.duoc.pedidos360.kafkaadmin.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import cl.duoc.pedidos360.kafkaadmin.dto.CreateTopicRequest;
import cl.duoc.pedidos360.kafkaadmin.dto.TopicDetail;
import cl.duoc.pedidos360.kafkaadmin.dto.TopicSummary;
import cl.duoc.pedidos360.kafkaadmin.dto.UpdateTopicConfigsRequest;
import cl.duoc.pedidos360.kafkaadmin.exception.BrokerUnavailableException;
import cl.duoc.pedidos360.kafkaadmin.exception.InvalidAdminRequestException;
import cl.duoc.pedidos360.kafkaadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.kafkaadmin.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(TopicAdminController.class)
class TopicAdminControllerTest extends ControllerTestSupport {
    private static final String TOPICS = "/api/v1/kafka-admin/topics";

    private static TopicDetail topic(String name) {
        return new TopicDetail(name, "id", false, false, 3,
                List.of(new TopicDetail.PartitionInfo(0, 1, List.of(1, 2, 3), List.of(1, 2, 3))),
                Map.of("retention.ms", "86400000"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void requiresTokenAdminRoleAndScope() throws Exception {
        mvc.perform(get(TOPICS)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(get(TOPICS).with(operator())).andExpect(status().isForbidden());
        mvc.perform(get(TOPICS).with(adminWithoutScope())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void listsTopics() throws Exception {
        when(service.listTopics(true)).thenReturn(List.of(new TopicSummary("orders.events", 3, 3, false, true)));
        mvc.perform(get(TOPICS).param("includeInternal", "true").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("orders.events"))
                .andExpect(jsonPath("$[0].protectedTopic").value(true));
    }

    @Test
    void createsATopicAndReturnsItsLocation() throws Exception {
        when(service.createTopic(any())).thenReturn(topic("demo.ep5"));
        mvc.perform(json(post(TOPICS), """
                        {"name":"demo.ep5","partitions":3,"replicationFactor":3,
                         "configs":{"retention.ms":"86400000","cleanup.policy":"delete"}}""").with(admin()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(TOPICS + "/demo.ep5")))
                .andExpect(jsonPath("$.partitions[0].inSyncReplicas.length()").value(3));
        verify(service).createTopic(new CreateTopicRequest("demo.ep5", 3, 3,
                Map.of("retention.ms", "86400000", "cleanup.policy", "delete")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"partitions\":3,\"replicationFactor\":3}",
            "{\"name\":\"\",\"partitions\":3,\"replicationFactor\":3}",
            "{\"name\":\"__interno\",\"partitions\":3,\"replicationFactor\":3}",
            "{\"name\":\"con espacio\",\"partitions\":3,\"replicationFactor\":3}",
            "{\"name\":\"demo\",\"partitions\":0,\"replicationFactor\":3}",
            "{\"name\":\"demo\",\"partitions\":51,\"replicationFactor\":3}",
            "{\"name\":\"demo\",\"partitions\":3,\"replicationFactor\":4}",
            "{\"name\":\"demo\",\"partitions\":3}",
            "{\"name\":\"demo\",\"partitions\":3,\"replicationFactor\":3,\"configs\":{\"retention.ms\":\"\"}}",
            "{\"name\":\"demo\",\"partitions\":3,\"replicationFactor\":3,\"desconocido\":1}",
            "{\"name\":\"demo\",\"partitions\":\"tres\",\"replicationFactor\":3}",
            "{no es json"})
    void rejectsInvalidTopicRequestsWithAClearMessage(String body) throws Exception {
        mvc.perform(json(post(TOPICS), body).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void mapsDomainErrorsToHttpStatus() throws Exception {
        when(service.createTopic(any())).thenThrow(new ResourceConflictException("ya existe"));
        mvc.perform(json(post(TOPICS), "{\"name\":\"demo\",\"partitions\":1,\"replicationFactor\":1}").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("ya existe"));
        when(service.getTopic("nope")).thenThrow(new ResourceNotFoundException("no existe"));
        mvc.perform(get(TOPICS + "/nope").with(admin())).andExpect(status().isNotFound());
        when(service.getTopic("caido")).thenThrow(new BrokerUnavailableException("Kafka no respondió", null));
        mvc.perform(get(TOPICS + "/caido").with(admin())).andExpect(status().isServiceUnavailable());
        when(service.increasePartitions("demo", 2)).thenThrow(new InvalidAdminRequestException("solo aumentar"));
        mvc.perform(json(patch(TOPICS + "/demo/partitions"), "{\"totalPartitions\":2}").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("solo aumentar"));
    }

    @Test
    void updatesConfigsIncrementally() throws Exception {
        when(service.updateTopicConfigs(eq("demo"), any())).thenReturn(topic("demo"));
        mvc.perform(json(patch(TOPICS + "/demo/configs"), "{\"set\":{\"retention.ms\":\"3600000\"},\"reset\":[\"segment.ms\"]}")
                        .with(admin()))
                .andExpect(status().isOk());
        verify(service).updateTopicConfigs("demo",
                new UpdateTopicConfigsRequest(Map.of("retention.ms", "3600000"), java.util.Set.of("segment.ms")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"set\":{},\"reset\":[]}",
            "{\"set\":{\"retention.ms\":\"1\"},\"reset\":[\"retention.ms\"]}"})
    void rejectsEmptyOrContradictoryConfigChanges(String body) throws Exception {
        mvc.perform(json(patch(TOPICS + "/demo/configs"), body).with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("set")));
        verifyNoInteractions(service);
    }

    @Test
    void increasesPartitionsAndValidatesTheTotal() throws Exception {
        when(service.increasePartitions("demo", 6)).thenReturn(topic("demo"));
        mvc.perform(json(patch(TOPICS + "/demo/partitions"), "{\"totalPartitions\":6}").with(admin()))
                .andExpect(status().isOk());
        mvc.perform(json(patch(TOPICS + "/demo/partitions"), "{\"totalPartitions\":1}").with(admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(patch(TOPICS + "/demo/partitions"), "{\"totalPartitions\":51}").with(admin()))
                .andExpect(status().isBadRequest());
        verify(service).increasePartitions("demo", 6);
    }

    @Test
    void deletesTopicsButProtectsTheBaseOnes() throws Exception {
        mvc.perform(delete(TOPICS + "/demo").with(admin())).andExpect(status().isNoContent());
        verify(service).deleteTopic("demo");
        doThrow(new ResourceConflictException("protegido")).when(service).deleteTopic("orders.events");
        mvc.perform(delete(TOPICS + "/orders.events").with(admin())).andExpect(status().isConflict());
        mvc.perform(delete(TOPICS + "/__consumer_offsets").with(admin())).andExpect(status().isBadRequest());
    }

    @Test
    void validatesPathNames() throws Exception {
        mvc.perform(get(TOPICS + "/" + "x".repeat(250)).with(admin())).andExpect(status().isBadRequest());
        mvc.perform(json(patch(TOPICS + "/__consumer_offsets/configs"), "{\"set\":{\"retention.ms\":\"1\"}}").with(admin()))
                .andExpect(status().isBadRequest());
        verify(service, org.mockito.Mockito.never()).updateTopicConfigs(anyString(), any());
        verify(service, org.mockito.Mockito.never()).increasePartitions(anyString(), anyInt());
    }
}
