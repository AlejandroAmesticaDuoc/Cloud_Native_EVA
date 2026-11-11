package cl.duoc.pedidos360.kafkaadmin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import cl.duoc.pedidos360.kafkaadmin.dto.ClusterResponse;
import cl.duoc.pedidos360.kafkaadmin.dto.ConsumerGroupDetail;
import cl.duoc.pedidos360.kafkaadmin.dto.ConsumerGroupSummary;
import cl.duoc.pedidos360.kafkaadmin.dto.OffsetResetStrategy;
import cl.duoc.pedidos360.kafkaadmin.dto.ResetOffsetsRequest;
import cl.duoc.pedidos360.kafkaadmin.dto.ResetOffsetsResponse;
import cl.duoc.pedidos360.kafkaadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.kafkaadmin.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;

@WebMvcTest({ConsumerGroupAdminController.class, ClusterAdminController.class})
class ConsumerGroupAdminControllerTest extends ControllerTestSupport {
    private static final String GROUPS = "/api/v1/kafka-admin/consumer-groups";

    @Test
    void listsGroupsAndShowsLagPerPartition() throws Exception {
        when(service.listConsumerGroups()).thenReturn(List.of(new ConsumerGroupSummary("pedidos360-audit-v1", "STABLE")));
        mvc.perform(get(GROUPS).with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("STABLE"));
        when(service.getConsumerGroup("pedidos360-audit-v1")).thenReturn(new ConsumerGroupDetail("pedidos360-audit-v1",
                "STABLE", "cooperative-sticky", 2,
                List.of(new ConsumerGroupDetail.Member("m-1", "pedidos360-audit", "/10.0.0.5", List.of("orders.events-0"))),
                List.of(new ConsumerGroupDetail.PartitionLag("orders.events", 0, 40, 45, 5)), 5));
        mvc.perform(get(GROUPS + "/pedidos360-audit-v1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsets[0].lag").value(5))
                .andExpect(jsonPath("$.totalLag").value(5))
                .andExpect(jsonPath("$.members[0].assignedPartitions[0]").value("orders.events-0"));
    }

    @Test
    void resetsOffsetsOnlyWithAValidStrategy() throws Exception {
        when(service.resetOffsets(eq("demo"), any())).thenReturn(new ResetOffsetsResponse("demo", "orders.events",
                OffsetResetStrategy.EARLIEST, List.of(new ResetOffsetsResponse.PartitionOffset(0, 0))));
        mvc.perform(post(GROUPS + "/demo/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"orders.events\",\"strategy\":\"EARLIEST\"}").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offsets[0].offset").value(0));
        verify(service).resetOffsets("demo", new ResetOffsetsRequest("orders.events", OffsetResetStrategy.EARLIEST, null));
        mvc.perform(post(GROUPS + "/demo/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"orders.events\",\"strategy\":\"TIMESTAMP\",\"timestamp\":\"2026-11-10T12:00:00Z\"}")
                        .with(admin()))
                .andExpect(status().isOk());
        verify(service).resetOffsets("demo", new ResetOffsetsRequest("orders.events", OffsetResetStrategy.TIMESTAMP,
                Instant.parse("2026-11-10T12:00:00Z")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"strategy\":\"EARLIEST\"}", "{\"topic\":\"orders.events\"}",
            "{\"topic\":\"orders.events\",\"strategy\":\"AYER\"}",
            "{\"topic\":\"orders.events\",\"strategy\":\"TIMESTAMP\"}",
            "{\"topic\":\"orders.events\",\"strategy\":\"EARLIEST\",\"timestamp\":\"2026-11-10T12:00:00Z\"}",
            "{\"topic\":\"orders events\",\"strategy\":\"EARLIEST\"}"})
    void rejectsInvalidResetRequests(String body) throws Exception {
        mvc.perform(post(GROUPS + "/demo/offsets/reset").contentType(MediaType.APPLICATION_JSON).content(body).with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void mapsGroupErrors() throws Exception {
        when(service.getConsumerGroup("nope")).thenThrow(new ResourceNotFoundException("no existe"));
        mvc.perform(get(GROUPS + "/nope").with(admin())).andExpect(status().isNotFound());
        doThrow(new ResourceConflictException("activos")).when(service).deleteConsumerGroup("activo");
        mvc.perform(delete(GROUPS + "/activo").with(admin())).andExpect(status().isConflict());
        mvc.perform(delete(GROUPS + "/viejo").with(admin())).andExpect(status().isNoContent());
        verify(service).deleteConsumerGroup("viejo");
        mvc.perform(get(GROUPS + "/con espacio").with(admin())).andExpect(status().isBadRequest());
    }

    @Test
    void showsTheClusterOnlyToAdmins() throws Exception {
        when(service.cluster()).thenReturn(new ClusterResponse("abc", 1,
                List.of(new ClusterResponse.Broker(1, "kafka-1", 19092, null))));
        mvc.perform(get("/api/v1/kafka-admin/cluster").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.brokers[0].host").value("kafka-1"));
        mvc.perform(get("/api/v1/kafka-admin/cluster").with(operator())).andExpect(status().isForbidden());
    }
}
