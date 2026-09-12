package cl.duoc.pedidos360.bff.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class IntegerRequestTest {
    @Autowired MockMvc mvc;

    @Test
    void rejectsFractionalQuantity() throws Exception {
        mvc.perform(post("/api/v1/orders").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_pedidos360.access"), new SimpleGrantedAuthority("ROLE_CLIENTE")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":1,\"quantity\":1.5}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFractionalProductId() throws Exception {
        mvc.perform(post("/api/v1/orders").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_pedidos360.access"), new SimpleGrantedAuthority("ROLE_CLIENTE")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":1.5,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFractionalStock() throws Exception {
        mvc.perform(patch("/api/v1/catalog/1/stock").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_pedidos360.access"), new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"stock\":1.5}"))
                .andExpect(status().isBadRequest());
    }
}
