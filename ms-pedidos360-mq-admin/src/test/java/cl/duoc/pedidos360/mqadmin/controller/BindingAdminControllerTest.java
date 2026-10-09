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

import cl.duoc.pedidos360.mqadmin.dto.BindingResponse;
import cl.duoc.pedidos360.mqadmin.exception.InvalidAdminRequestException;
import cl.duoc.pedidos360.mqadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.mqadmin.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(BindingAdminController.class)
class BindingAdminControllerTest extends ControllerTestSupport {
    private static final String BINDINGS = "/api/v1/mq-admin/bindings";

    private static MockHttpServletRequestBuilder create(String body) {
        return post(BINDINGS).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void requiresAuthenticationAndAdminRole() throws Exception {
        mvc.perform(get(BINDINGS)).andExpect(status().isUnauthorized());
        mvc.perform(get(BINDINGS).with(operator())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void listsBindingsWithOptionalFilters() throws Exception {
        when(service.listBindings("cmd.direct", null)).thenReturn(List.of(
                new BindingResponse("cmd.direct", "q.cmd.email", "email.send", Map.of())));
        mvc.perform(get(BINDINGS).param("exchange", "cmd.direct").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].queue").value("q.cmd.email"))
                .andExpect(jsonPath("$[0].routingKey").value("email.send"));
        mvc.perform(get(BINDINGS).with(admin())).andExpect(status().isOk());
        verify(service).listBindings(null, null);
        mvc.perform(get(BINDINGS).param("queue", "cola inválida").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("queue: La cola debe tener")));
    }

    @Test
    void createsBindingWithEncodedLocation() throws Exception {
        when(service.createBinding(any())).thenReturn(new BindingResponse("ex.demo", "q.demo", "demo.#", Map.of()));
        mvc.perform(create("{\"exchange\":\"ex.demo\",\"queue\":\"q.demo\",\"routingKey\":\"demo.#\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        endsWith(BINDINGS + "?exchange=ex.demo&queue=q.demo&routingKey=demo.%23")))
                .andExpect(jsonPath("$.routingKey").value("demo.#"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', textBlock = """
            sin exchange         | {"queue":"q.demo","routingKey":"a"}                       | exchange: El exchange es obligatorio
            cola vacía           | {"exchange":"ex.demo","queue":"","routingKey":"a"}        | queue: La cola es obligatoria
            sin routing key      | {"exchange":"ex.demo","queue":"q.demo"}                   | routingKey: La routingKey es obligatoria
            comodín pegado       | {"exchange":"ex.demo","queue":"q.demo","routingKey":"demo.*x"} | La routingKey debe estar formada por segmentos
            punto final          | {"exchange":"ex.demo","queue":"q.demo","routingKey":"demo."}   | La routingKey debe estar formada por segmentos
            exchange reservado   | {"exchange":"amq.topic","queue":"q.demo","routingKey":"a"} | prefijo reservado 'amq.'
            """)
    void rejectsInvalidBindings(String scenario, String body, String expected) throws Exception {
        mvc.perform(create(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(expected)));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsWildcardsOnNonTopicExchanges() throws Exception {
        when(service.createBinding(any())).thenThrow(new InvalidAdminRequestException(
                "Los comodines '*' y '#' solo se permiten en exchanges topic; 'cmd.direct' es de tipo DIRECT"));
        mvc.perform(create("{\"exchange\":\"cmd.direct\",\"queue\":\"q.demo\",\"routingKey\":\"email.*\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("solo se permiten en exchanges topic")));
    }

    @Test
    void reportsMissingResourcesAndDuplicates() throws Exception {
        when(service.createBinding(any()))
                .thenThrow(new ResourceNotFoundException("El exchange 'ex.none' no existe"))
                .thenThrow(new ResourceConflictException("El binding ex.demo --a--> q.demo ya existe"));
        mvc.perform(create("{\"exchange\":\"ex.none\",\"queue\":\"q.demo\",\"routingKey\":\"a\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(create("{\"exchange\":\"ex.demo\",\"queue\":\"q.demo\",\"routingKey\":\"a\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void deletesBindings() throws Exception {
        mvc.perform(delete(BINDINGS).with(admin())
                        .param("exchange", "ex.demo").param("queue", "q.demo").param("routingKey", "demo.#"))
                .andExpect(status().isNoContent());
        verify(service).deleteBinding("ex.demo", "q.demo", "demo.#");
        mvc.perform(delete(BINDINGS).with(admin())
                        .param("exchange", "ex.fanout").param("queue", "q.demo").param("routingKey", ""))
                .andExpect(status().isNoContent());
        verify(service).deleteBinding("ex.fanout", "q.demo", "");
    }

    @Test
    void validatesDeleteParameters() throws Exception {
        mvc.perform(delete(BINDINGS).with(admin()).param("exchange", "ex.demo").param("queue", "q.demo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el parámetro obligatorio 'routingKey'"));
        doThrow(new ResourceNotFoundException("El binding ex.demo --x--> q.demo no existe")).when(service)
                .deleteBinding("ex.demo", "q.demo", "x");
        mvc.perform(delete(BINDINGS).with(admin())
                        .param("exchange", "ex.demo").param("queue", "q.demo").param("routingKey", "x"))
                .andExpect(status().isNotFound());
    }
}
