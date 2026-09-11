package cl.duoc.pedidos360.bff.security;

import java.util.Set;

public record CurrentUser(
        String userId,
        Set<String> roles
) {

    public CurrentUser {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean canManageAllOrders() {
        return hasRole("ADMIN")
                || hasRole("OPERADOR");
    }
}