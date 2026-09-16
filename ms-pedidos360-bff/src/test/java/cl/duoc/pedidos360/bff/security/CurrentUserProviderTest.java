package cl.duoc.pedidos360.bff.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserProviderTest {

    private final CurrentUserProvider provider =
            new CurrentUserProvider();

    @Test
    void shouldPreferObjectIdAndExtractRoles() {
        Jwt jwt = Jwt.withTokenValue("token-prueba")
                .header("alg", "none")
                .subject("subject-secundario")
                .claim("oid", "object-id-principal")
                .build();

        JwtAuthenticationToken authentication =
                new JwtAuthenticationToken(
                        jwt,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "SCOPE_pedidos360.access"
                                ),
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                ),
                                new SimpleGrantedAuthority(
                                        "ROLE_CLIENTE"
                                )
                        )
                );

        CurrentUser currentUser =
                provider.from(authentication);

        assertEquals(
                "object-id-principal",
                currentUser.userId()
        );

        assertEquals(
                2,
                currentUser.roles().size()
        );

        assertTrue(currentUser.hasRole("ADMIN"));
        assertTrue(currentUser.hasRole("CLIENTE"));
        assertTrue(currentUser.canManageAllOrders());
    }

    @Test
    void shouldUseSubjectWhenObjectIdIsMissing() {
        Jwt jwt = Jwt.withTokenValue("token-prueba")
                .header("alg", "none")
                .subject("subject-principal")
                .build();

        JwtAuthenticationToken authentication =
                new JwtAuthenticationToken(
                        jwt,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_CLIENTE"
                                )
                        )
                );

        CurrentUser currentUser =
                provider.from(authentication);

        assertEquals(
                "subject-principal",
                currentUser.userId()
        );

        assertTrue(currentUser.hasRole("CLIENTE"));
    }

    @Test
    void shouldRejectTokenWithoutUserIdentifier() {
        Jwt jwt = Jwt.withTokenValue("token-prueba")
                .header("alg", "none")
                .claim(
                        "scp",
                        "pedidos360.access"
                )
                .build();

        JwtAuthenticationToken authentication =
                new JwtAuthenticationToken(
                        jwt,
                        List.of()
                );

        assertThrows(
                IllegalStateException.class,
                () -> provider.from(authentication)
        );
    }
}