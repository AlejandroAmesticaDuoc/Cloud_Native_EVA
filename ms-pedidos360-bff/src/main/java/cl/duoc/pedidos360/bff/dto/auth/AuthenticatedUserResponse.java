package cl.duoc.pedidos360.bff.dto.auth;

import java.util.List;

public record AuthenticatedUserResponse(
        String userId,
        String subject,
        String tenantId,
        String username,
        String displayName,
        List<String> roles,
        List<String> scopes
) {
}