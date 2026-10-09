package cl.duoc.pedidos360.mqadmin.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

import cl.duoc.pedidos360.mqadmin.dto.ExchangeResponse;
import cl.duoc.pedidos360.mqadmin.exception.ProtectedResourceException;
import cl.duoc.pedidos360.mqadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.mqadmin.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;

@WebMvcTest(ExchangeAdminController.class)
class ExchangeAdminControllerTest extends ControllerTestSupport {
    private static final String EXCHANGES = "/api/v1/mq-admin/exchanges";

    @Test
    void requiresAuthenticationAndAdminRole() throws Exception {
        mvc.perform(get(EXCHANGES)).andExpect(status().isUnauthorized());
        mvc.perform(get(EXCHANGES).with(operator())).andExpect(status().isForbidden());
        mvc.perform(delete(EXCHANGES + "/ex.demo").with(adminWithoutScope())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void listsExchanges() throws Exception {
        when(service.listExchanges()).thenReturn(List.of(
                new ExchangeResponse("cmd.direct", "DIRECT", true, false, false, Map.of(), true)));
        mvc.perform(get(EXCHANGES).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("cmd.direct"))
                .andExpect(jsonPath("$[0].protectedResource").value(true));
    }

    @Test
    void createsExchangeWithLocation() throws Exception {
        when(service.createExchange(any())).thenReturn(
                new ExchangeResponse("ex.demo.ep3", "TOPIC", true, false, false, Map.of(), false));
        mvc.perform(post(EXCHANGES).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ex.demo.ep3\",\"type\":\"TOPIC\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(EXCHANGES + "/ex.demo.ep3")))
                .andExpect(jsonPath("$.type").value("TOPIC"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', textBlock = """
            nombre vacío      | {"name":"","type":"DIRECT"}           | name: El nombre del exchange es obligatorio
            prefijo amq.      | {"name":"amq.custom","type":"DIRECT"} | prefijo reservado 'amq.'
            sin tipo          | {"name":"ex.demo"}                    | type: El tipo de exchange es obligatorio
            tipo inexistente  | {"name":"ex.demo","type":"QUEUE"}     | valores permitidos: DIRECT, TOPIC, FANOUT, HEADERS
            durable no lógico | {"name":"ex.demo","type":"DIRECT","durable":"talvez"} | El campo 'durable' tiene un tipo de dato inválido
            """)
    void rejectsInvalidExchanges(String scenario, String body, String expected) throws Exception {
        mvc.perform(post(EXCHANGES).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(expected)));
        verifyNoInteractions(service);
    }

    @Test
    void returnsConflictForDuplicatedExchange() throws Exception {
        when(service.createExchange(any())).thenThrow(new ResourceConflictException("El exchange 'ex.demo' ya existe"));
        mvc.perform(post(EXCHANGES).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ex.demo\",\"type\":\"FANOUT\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void deletesExchanges() throws Exception {
        mvc.perform(delete(EXCHANGES + "/ex.demo").param("ifUnused", "true").with(admin()))
                .andExpect(status().isNoContent());
        verify(service).deleteExchange("ex.demo", true);
    }

    @Test
    void reportsMissingAndProtectedExchanges() throws Exception {
        doThrow(new ResourceNotFoundException("El exchange 'ex.none' no existe")).when(service)
                .deleteExchange("ex.none", false);
        doThrow(new ProtectedResourceException("El exchange", "cmd.dead.dlx")).when(service)
                .deleteExchange("cmd.dead.dlx", false);
        mvc.perform(delete(EXCHANGES + "/ex.none").with(admin())).andExpect(status().isNotFound());
        mvc.perform(delete(EXCHANGES + "/cmd.dead.dlx").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("no se puede eliminar")));
        mvc.perform(delete(EXCHANGES + "/amq.topic").with(admin())).andExpect(status().isBadRequest());
    }
}
