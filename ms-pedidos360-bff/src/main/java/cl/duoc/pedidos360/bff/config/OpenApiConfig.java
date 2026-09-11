package cl.duoc.pedidos360.bff.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String SECURITY_SCHEME =
            "bearerAuth";

    @Bean
    public OpenAPI pedidos360OpenApi() {
        Info information = new Info()
                .title("Pedidos360 BFF")
                .version("v1")
                .description("""
                        API de entrada para autenticación, pedidos y catálogo.

                        Las operaciones requieren un Access Token de
                        Microsoft Entra ID con el scope pedidos360.access.
                        Los permisos por rol se validan en cada endpoint.
                        """);

        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("""
                        Ingresa el Access Token de Microsoft Entra ID
                        sin escribir el prefijo Bearer.
                        """);

        return new OpenAPI()
                .info(information)
                .components(new Components()
                        .addSecuritySchemes(
                                SECURITY_SCHEME,
                                bearerScheme
                        ))
                .addSecurityItem(new SecurityRequirement()
                        .addList(SECURITY_SCHEME));
    }
}