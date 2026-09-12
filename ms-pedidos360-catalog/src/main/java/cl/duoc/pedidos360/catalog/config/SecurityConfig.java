package cl.duoc.pedidos360.catalog.config;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import cl.duoc.pedidos360.catalog.security.AuthenticationErrorHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    private static final String SCOPE = "SCOPE_pedidos360.access";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationErrorHandler errors,
            JwtAuthenticationConverter converter,
            @Value("${springdoc.api-docs.enabled:false}") boolean docsEnabled) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**")
                        .access((authentication, context) -> new AuthorizationDecision(docsEnabled))
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog", "/api/v1/catalog/{id}")
                        .hasAuthority(SCOPE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/catalog").access(scopeAndRole("ADMIN"))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/catalog/{id}").access(scopeAndRole("ADMIN"))
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/catalog/{id}/stock").access(scopeAndRole("ADMIN"))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/catalog/{id}").access(scopeAndRole("ADMIN"))
                        .requestMatchers(HttpMethod.POST, "/internal/v1/catalog/stock/deductions",
                                "/internal/v1/catalog/stock/deductions/{orderId}/release")
                        .access(scopeAndRole("ADMIN", "OPERADOR"))
                        .anyRequest().denyAll())
                .exceptionHandling(errorsConfig -> errorsConfig.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(errors).accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new DelegatingJwtGrantedAuthoritiesConverter(
                new JwtGrantedAuthoritiesConverter(), roles));
        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience) {
        // Descubrimiento diferido: revisar salud no requiere que Entra esté disponible.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer),
                    new JwtClaimValidator<List<String>>("aud", value -> value != null && value.contains(audience)),
                    new JwtClaimValidator<Instant>("exp", Objects::nonNull)));
            return decoder;
        });
    }

    private static AuthorizationManager<RequestAuthorizationContext> scopeAndRole(String... roles) {
        return AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAuthority(SCOPE),
                AuthorityAuthorizationManager.hasAnyRole(roles));
    }
}
