package cl.duoc.pedidos360.mqadmin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import cl.duoc.pedidos360.mqadmin.dto.CreateQueueRequest;
import cl.duoc.pedidos360.mqadmin.dto.PurgeResponse;
import cl.duoc.pedidos360.mqadmin.dto.QueueResponse;
import cl.duoc.pedidos360.mqadmin.dto.QueueType;
import cl.duoc.pedidos360.mqadmin.exception.BrokerUnavailableException;
import cl.duoc.pedidos360.mqadmin.exception.ProtectedResourceException;
import cl.duoc.pedidos360.mqadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.mqadmin.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(QueueAdminController.class)
class QueueAdminControllerTest extends ControllerTestSupport {
    private static final String QUEUES = "/api/v1/mq-admin/queues";

    private static QueueResponse queue(String name) {
        return new QueueResponse(name, "CLASSIC", true, false, Map.of(), 0, 0, 0, 0, false);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void rejectsRequestsWithoutTokenWithTheCommonErrorFormat() throws Exception {
        mvc.perform(get(QUEUES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Se requiere autenticación para acceder a este recurso"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void requiresAdminRoleAndScope() throws Exception {
        mvc.perform(get(QUEUES).with(operator())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(get(QUEUES).with(adminWithoutScope())).andExpect(status().isForbidden());
        mvc.perform(json(post(QUEUES).with(operator()), "{\"name\":\"q.demo\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void listsAndReadsQueues() throws Exception {
        when(service.listQueues()).thenReturn(List.of(queue("q.a"), queue("q.b")));
        when(service.getQueue("q.cmd.email")).thenReturn(new QueueResponse("q.cmd.email", "CLASSIC", true, false,
                Map.of("x-dead-letter-exchange", "cmd.dead.dlx"), 3, 2, 1, 1, true));
        mvc.perform(get(QUEUES).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].name").value("q.b"));
        mvc.perform(get(QUEUES + "/q.cmd.email").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").value(3))
                .andExpect(jsonPath("$.consumers").value(1))
                .andExpect(jsonPath("$.protectedResource").value(true))
                .andExpect(jsonPath("$.arguments['x-dead-letter-exchange']").value("cmd.dead.dlx"));
    }

    @Test
    void returnsNotFoundForMissingQueue() throws Exception {
        when(service.getQueue("q.missing")).thenThrow(new ResourceNotFoundException("La cola 'q.missing' no existe"));
        mvc.perform(get(QUEUES + "/q.missing").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("La cola 'q.missing' no existe"))
                .andExpect(jsonPath("$.path").value(QUEUES + "/q.missing"));
    }

    @Test
    void createsQueueWithDefaultsAndLocation() throws Exception {
        when(service.createQueue(any())).thenReturn(queue("q.demo.ep3"));
        mvc.perform(json(post(QUEUES).with(admin()), "{\"name\":\"q.demo.ep3\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(QUEUES + "/q.demo.ep3")))
                .andExpect(jsonPath("$.name").value("q.demo.ep3"));
        ArgumentCaptor<CreateQueueRequest> captor = ArgumentCaptor.forClass(CreateQueueRequest.class);
        verify(service).createQueue(captor.capture());
        CreateQueueRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(QueueType.CLASSIC);
        assertThat(sent.durable()).isTrue();
        assertThat(sent.autoDelete()).isFalse();
    }

    @Test
    void acceptsAFullQuorumConfiguration() throws Exception {
        when(service.createQueue(any())).thenReturn(queue("q.demo.quorum"));
        mvc.perform(json(post(QUEUES).with(admin()), """
                {"name":"q.demo.quorum","type":"QUORUM","durable":true,"autoDelete":false,
                 "deadLetterExchange":"cmd.dead.dlx","deadLetterRoutingKey":"demo.failed",
                 "messageTtlMs":604800000,"maxLength":10000}
                """)).andExpect(status().isCreated());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', textBlock = """
            nombre vacío            | {"name":""}                                            | name: El nombre de la cola es obligatorio
            sin nombre              | {"type":"CLASSIC"}                                     | name: El nombre de la cola es obligatorio
            prefijo amq.            | {"name":"amq.mia"}                                     | prefijo reservado 'amq.'
            caracteres inválidos    | {"name":"cola con espacios"}                           | El nombre de la cola debe tener entre 1 y 255 caracteres
            tipo inválido           | {"name":"q.demo","type":"STREAM"}                      | valores permitidos: CLASSIC, QUORUM
            ttl negativo            | {"name":"q.demo","messageTtlMs":-5}                    | messageTtlMs debe ser mayor o igual a 1 ms
            ttl excesivo            | {"name":"q.demo","messageTtlMs":1209600001}            | messageTtlMs no puede superar 1209600000 ms
            largo cero              | {"name":"q.demo","maxLength":0}                        | maxLength debe ser mayor o igual a 1
            largo excesivo          | {"name":"q.demo","maxLength":1000001}                  | maxLength no puede superar 1000000
            DLRK sin DLX            | {"name":"q.demo","deadLetterRoutingKey":"demo.failed"} | deadLetterRoutingKey requiere indicar también deadLetterExchange
            DLRK con comodín        | {"name":"q.demo","deadLetterExchange":"cmd.dead.dlx","deadLetterRoutingKey":"demo.#"} | deadLetterRoutingKey debe estar formada por segmentos
            DLX reservado           | {"name":"q.demo","deadLetterExchange":"amq.direct"}    | deadLetterExchange debe tener entre 1 y 255 caracteres
            quorum no durable       | {"name":"q.demo","type":"QUORUM","durable":false}      | durable: Las colas QUORUM deben ser durables
            quorum autoDelete       | {"name":"q.demo","type":"QUORUM","autoDelete":true}    | autoDelete: Las colas QUORUM no admiten autoDelete=true
            clásica transitoria     | {"name":"q.demo","durable":false}                      | RabbitMQ 4.x no admite colas transitorias
            propiedad desconocida   | {"name":"q.demo","exclusive":true}                     | La propiedad 'exclusive' no es reconocida
            tipo de dato inválido   | {"name":"q.demo","maxLength":"mucho"}                  | El campo 'maxLength' tiene un tipo de dato inválido
            JSON mal formado        | {"name":                                               | no es un JSON válido
            """)
    void rejectsInvalidQueueRequestsWithClearMessages(String scenario, String body, String expected) throws Exception {
        mvc.perform(json(post(QUEUES).with(admin()), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString(expected)));
        verifyNoInteractions(service);
    }

    @Test
    void reportsOnlyTheRequiredRuleForAnEmptyName() throws Exception {
        mvc.perform(json(post(QUEUES).with(admin()), "{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(allOf(containsString("obligatorio"),
                        not(containsString("prefijo reservado")))));
    }

    @Test
    void returnsConflictWhenTheQueueAlreadyExists() throws Exception {
        when(service.createQueue(any())).thenThrow(new ResourceConflictException("La cola 'q.demo' ya existe"));
        mvc.perform(json(post(QUEUES).with(admin()), "{\"name\":\"q.demo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La cola 'q.demo' ya existe"));
    }

    @Test
    void deletesQueueWithPreconditions() throws Exception {
        mvc.perform(delete(QUEUES + "/q.demo").param("ifUnused", "true").with(admin()))
                .andExpect(status().isNoContent());
        verify(service).deleteQueue("q.demo", true, false);
    }

    @Test
    void protectsBaseTopologyAndReportsMissingQueues() throws Exception {
        doThrow(new ProtectedResourceException("La cola", "q.cmd.email")).when(service)
                .deleteQueue("q.cmd.email", false, false);
        doThrow(new ResourceNotFoundException("La cola 'q.missing' no existe")).when(service)
                .deleteQueue("q.missing", false, false);
        mvc.perform(delete(QUEUES + "/q.cmd.email").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("topología base")));
        mvc.perform(delete(QUEUES + "/q.missing").with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void validatesPathAndQueryParameters() throws Exception {
        mvc.perform(delete(QUEUES + "/amq.gen-123").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("name: El nombre de la cola")));
        mvc.perform(delete(QUEUES + "/q.demo").param("ifUnused", "quizas").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El valor 'quizas' no es válido para 'ifUnused'"));
        verify(service, never()).deleteQueue(anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void purgesMessages() throws Exception {
        when(service.purgeQueue("q.cmd.email.dlq")).thenReturn(new PurgeResponse("q.cmd.email.dlq", 4));
        mvc.perform(delete(QUEUES + "/q.cmd.email.dlq/messages").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value("q.cmd.email.dlq"))
                .andExpect(jsonPath("$.purged").value(4));
    }

    @Test
    void returnsServiceUnavailableWhenTheBrokerIsDown() throws Exception {
        when(service.listQueues()).thenThrow(new BrokerUnavailableException(
                "La API de management de RabbitMQ no está disponible", null));
        mvc.perform(get(QUEUES).with(admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("La API de management de RabbitMQ no está disponible"));
    }
}
