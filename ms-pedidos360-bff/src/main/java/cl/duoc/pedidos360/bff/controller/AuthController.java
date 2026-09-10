package cl.duoc.pedidos360.bff.controller;

import java.util.List;

import cl.duoc.pedidos360.bff.dto.auth.AuthenticatedUserResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String SCOPE_PREFIX = "SCOPE_";

    @GetMapping("/me")
    public AuthenticatedUserResponse getAuthenticatedUser(
            JwtAuthenticationToken authentication) {

        Jwt jwt = authentication.getToken();

        return new AuthenticatedUserResponse(
                resolveUserId(jwt),
                jwt.getSubject(),
                jwt.getClaimAsString("tid"),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("name"),
                extractAuthorities(authentication, ROLE_PREFIX),
                extractAuthorities(authentication, SCOPE_PREFIX)
        );
    }

    private String resolveUserId(Jwt jwt) {
        String objectId = jwt.getClaimAsString("oid");

        if (objectId == null || objectId.isBlank()) {
            return jwt.getSubject();
        }

        return objectId;
    }

    private List<String> extractAuthorities(
            JwtAuthenticationToken authentication,
            String prefix) {

        return authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(prefix))
                .map(authority -> authority.substring(
                        prefix.length()
                ))
                .sorted()
                .toList();
    }
}