package cl.duoc.pedidos360.orders.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import cl.duoc.pedidos360.orders.exception.ForbiddenOperationException;

public record CurrentUser(String id, Set<String> roles) {
    public static CurrentUser from(JwtAuthenticationToken authentication) {
        String oid = authentication.getToken().getClaimAsString("oid");
        String id = oid == null || oid.isBlank() ? authentication.getToken().getSubject() : oid;
        if (id == null || id.isBlank()) throw new ForbiddenOperationException();
        return new CurrentUser(id, authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).collect(Collectors.toUnmodifiableSet()));
    }

    public boolean managesAll() {
        return roles.contains("ROLE_ADMIN") || roles.contains("ROLE_OPERADOR");
    }

    public void verifyOwner(String customerId) {
        if (!managesAll() && !id.equals(customerId)) throw new ForbiddenOperationException();
    }
}
