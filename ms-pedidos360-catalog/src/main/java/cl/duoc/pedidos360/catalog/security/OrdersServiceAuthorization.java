package cl.duoc.pedidos360.catalog.security;

import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

@Component
public class OrdersServiceAuthorization implements AuthorizationManager<RequestAuthorizationContext> {
    private final String clientId;

    public OrdersServiceAuthorization(@Value("${catalog.orders-client-id}") String clientId) {
        this.clientId = clientId;
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        if (clientId.isBlank() || !(authentication.get() instanceof JwtAuthenticationToken jwt)) {
            return new AuthorizationDecision(false);
        }
        boolean authorized = clientId.equals(jwt.getToken().getClaim("azp"))
                && jwt.getToken().getClaim("scp") == null
                && jwt.getAuthorities().stream().anyMatch(authority ->
                    authority.getAuthority().equals("ROLE_CATALOG_STOCK_WRITE"));
        return new AuthorizationDecision(authorized);
    }
}
