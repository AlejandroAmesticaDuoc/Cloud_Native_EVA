package cl.duoc.pedidos360.mqadmin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import cl.duoc.pedidos360.mqadmin.config.SecurityConfig;
import cl.duoc.pedidos360.mqadmin.security.AuthenticationErrorHandler;
import cl.duoc.pedidos360.mqadmin.service.RabbitAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base de las pruebas @WebMvcTest: cadena de seguridad real, servicio simulado y JWT de prueba
 * con las autoridades que produce el convertidor (scope + rol).
 */
@Import({SecurityConfig.class, AuthenticationErrorHandler.class})
abstract class ControllerTestSupport {
    static final String SCOPE = "SCOPE_pedidos360.access";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RabbitAdminService service;

    static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority(SCOPE), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    static RequestPostProcessor operator() {
        return jwt().authorities(new SimpleGrantedAuthority(SCOPE), new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    static RequestPostProcessor adminWithoutScope() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
