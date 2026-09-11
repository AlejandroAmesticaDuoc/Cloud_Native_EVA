package cl.duoc.pedidos360.bff.config;

import cl.duoc.pedidos360.bff.security.JwtAccessDeniedHandler;
import cl.duoc.pedidos360.bff.security.JwtAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.DelegatingJwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String REQUIRED_SCOPE =
            "SCOPE_pedidos360.access";

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            JwtAccessDeniedHandler accessDeniedHandler,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            UrlBasedCorsConfigurationSource corsConfigurationSource,
            @Value("${springdoc.api-docs.enabled:false}")
            boolean apiDocsEnabled) throws Exception {

        http
                .cors(cors -> cors.configurationSource(
                        corsConfigurationSource
                ))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        ))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(
                                DispatcherType.ERROR
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui.html",
                                "/swagger-ui/**"
                        ).access((authentication, context) ->
                                new AuthorizationDecision(
                                        apiDocsEnabled
                                ))                        

                        .requestMatchers(
                                HttpMethod.GET,
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/auth/me"
                        ).hasAuthority(REQUIRED_SCOPE)

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/orders/{id}/status"
                        ).access(scopeAndAnyRole(
                                "ADMIN",
                                "OPERADOR"
                        ))

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/orders/{id}/cancel"
                        ).access(scopeAndAnyRole(
                                "ADMIN",
                                "OPERADOR",
                                "CLIENTE"
                        ))

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/orders"
                        ).access(scopeAndAnyRole(
                                "CLIENTE",
                                "OPERADOR"
                        ))

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/orders",
                                "/api/v1/orders/{id}"
                        ).access(scopeAndAnyRole(
                                "ADMIN",
                                "OPERADOR",
                                "CLIENTE"
                        ))

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/catalog"
                        ).access(scopeAndAnyRole("ADMIN"))

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/catalog/{id}"
                        ).access(scopeAndAnyRole("ADMIN"))

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/catalog/{id}/stock"
                        ).access(scopeAndAnyRole("ADMIN"))

                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/v1/catalog/{id}"
                        ).access(scopeAndAnyRole("ADMIN"))

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/catalog",
                                "/api/v1/catalog/{id}"
                        ).hasAuthority(REQUIRED_SCOPE)

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/reports/summary",
                                "/api/v1/reports/lead-time"
                        ).access(scopeAndAnyRole("ADMIN"))

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/audit",
                                "/api/v1/audit/orders/{orderId}"
                        ).access(scopeAndAnyRole(
                                "ADMIN",
                                "AUDITOR"
                        ))

                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                authenticationEntryPoint
                        )
                        .accessDeniedHandler(
                                accessDeniedHandler
                        ))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(
                                authenticationEntryPoint
                        )
                        .accessDeniedHandler(
                                accessDeniedHandler
                        )
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                ))
                );

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopesConverter =
                new JwtGrantedAuthoritiesConverter();

        JwtGrantedAuthoritiesConverter rolesConverter =
                new JwtGrantedAuthoritiesConverter();

        rolesConverter.setAuthoritiesClaimName("roles");
        rolesConverter.setAuthorityPrefix("ROLE_");

        DelegatingJwtGrantedAuthoritiesConverter authoritiesConverter =
                new DelegatingJwtGrantedAuthoritiesConverter(
                        scopesConverter,
                        rolesConverter
                );

        JwtAuthenticationConverter authenticationConverter =
                new JwtAuthenticationConverter();

        authenticationConverter.setJwtGrantedAuthoritiesConverter(
                authoritiesConverter
        );

        return authenticationConverter;
    }

    private static AuthorizationManager<RequestAuthorizationContext>
            scopeAndAnyRole(String... roles) {

        return AuthorizationManagers
                .<RequestAuthorizationContext>allOf(
                        AuthorityAuthorizationManager.hasAuthority(
                                REQUIRED_SCOPE
                        ),
                        AuthorityAuthorizationManager.hasAnyRole(
                                roles
                        )
                );
    }
}