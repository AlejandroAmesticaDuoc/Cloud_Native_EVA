package cl.duoc.pedidos360.mqadmin.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import cl.duoc.pedidos360.mqadmin.dto.DeadLetterQueueStatus;
import cl.duoc.pedidos360.mqadmin.dto.ReplayResponse;
import cl.duoc.pedidos360.mqadmin.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;

@WebMvcTest(DeadLetterQueueController.class)
class DeadLetterQueueControllerTest extends ControllerTestSupport {
    private static final String DLQ = "/api/v1/mq-admin/dead-letter-queues";

    @Test
    void requiresAuthenticationAndAdminRole() throws Exception {
        mvc.perform(get(DLQ)).andExpect(status().isUnauthorized());
        mvc.perform(post(DLQ + "/q.cmd.email.dlq/replay").with(operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxMessages\":1}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void reportsDepthThresholdAndAlert() throws Exception {
        when(service.deadLetterQueues()).thenReturn(List.of(
                new DeadLetterQueueStatus("q.cmd.email.dlq", true, 2, 1, true),
                new DeadLetterQueueStatus("q.audit.dead-letters.dlq", false, 0, 1, false)));
        mvc.perform(get(DLQ).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].queue").value("q.cmd.email.dlq"))
                .andExpect(jsonPath("$[0].messages").value(2))
                .andExpect(jsonPath("$[0].threshold").value(1))
                .andExpect(jsonPath("$[0].alert").value(true))
                .andExpect(jsonPath("$[1].exists").value(false));
    }

    @Test
    void replaysMessages() throws Exception {
        when(service.replayDeadLetters("q.cmd.email.dlq", 10)).thenReturn(new ReplayResponse(9, 1));
        mvc.perform(post(DLQ + "/q.cmd.email.dlq/replay").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxMessages\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(9))
                .andExpect(jsonPath("$.failed").value(1));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', textBlock = """
            cero        | {"maxMessages":0}   | maxMessages debe ser mayor o igual a 1
            excesivo    | {"maxMessages":101} | maxMessages no puede superar 100
            ausente     | {}                  | maxMessages es obligatorio
            """)
    void validatesReplayLimits(String scenario, String body, String expected) throws Exception {
        mvc.perform(post(DLQ + "/q.cmd.email.dlq/replay").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(expected)));
        verify(service, never()).replayDeadLetters(anyString(), anyInt());
    }

    @Test
    void rejectsQueuesThatAreNotConfiguredDeadLetterQueues() throws Exception {
        when(service.replayDeadLetters("q.demo", 5)).thenThrow(new ResourceNotFoundException(
                "La cola 'q.demo' no es una DLQ configurada en mq-admin"));
        mvc.perform(post(DLQ + "/q.demo/replay").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxMessages\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("no es una DLQ configurada")));
        mvc.perform(post(DLQ + "/q.cmd.email.dlq/replay").with(admin())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cuerpo de la solicitud")));
    }
}
