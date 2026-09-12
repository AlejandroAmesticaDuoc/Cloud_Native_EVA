package cl.duoc.pedidos360.bff.config;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public class JwtDecoderConfig {
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        var jwt = properties.getJwt();
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(jwt.getIssuerUri());
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(jwt.getIssuerUri()),
                    new JwtClaimValidator<List<String>>("aud", audiences -> audiences != null
                            && jwt.getAudiences() != null && !jwt.getAudiences().isEmpty()
                            && audiences.containsAll(jwt.getAudiences())),
                    new JwtClaimValidator<Instant>("exp", Objects::nonNull)));
            return decoder;
        });
    }
}
