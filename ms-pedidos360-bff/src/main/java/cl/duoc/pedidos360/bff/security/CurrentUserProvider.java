package cl.duoc.pedidos360.bff.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    private static final String ROLE_PREFIX =
            "ROLE_";

    public CurrentUser from(
            JwtAuthenticationToken authentication) {

        validateAuthentication(authentication);

        String objectId = authentication
                .getToken()
                .getClaimAsString("oid");

        String userId = objectId == null
                || objectId.isBlank()
                ? authentication.getToken().getSubject()
                : objectId;

        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException(
                    "El token no contiene un identificador de usuario"
            );
        }

        Set<String> roles = authentication
                .getAuthorities()
                .stream()
                .map(authority ->
                        authority.getAuthority())
                .filter(authority ->
                        authority.startsWith(ROLE_PREFIX))
                .map(authority ->
                        authority.substring(
                                ROLE_PREFIX.length()
                        ))
                .collect(Collectors.toUnmodifiableSet());

        return new CurrentUser(
                userId,
                roles
        );
    }

    private void validateAuthentication(
            Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()) {

            throw new IllegalStateException(
                    "No existe un usuario autenticado"
            );
        }
    }
}