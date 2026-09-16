package cl.duoc.pedidos360.orders.security;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import cl.duoc.pedidos360.orders.exception.CatalogUnavailableException;

@Component
public class ServiceTokenProvider {
    private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager;
    private final InMemoryOAuth2AuthorizedClientService clients;

    public ServiceTokenProvider(
            @Value("${orders.catalog.client-id}") String clientId,
            @Value("${orders.catalog.client-secret}") String clientSecret,
            @Value("${orders.catalog.token-uri}") String tokenUri,
            @Value("${orders.catalog.scope}") String scope) {
        if (clientId.isBlank() || clientSecret.isBlank() || tokenUri.isBlank() || scope.isBlank()) {
            manager = null;
            clients = null;
            return;
        }
        URI uri = URI.create(tokenUri);
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())))) {
            throw new IllegalArgumentException("El endpoint OAuth2 requiere HTTPS");
        }
        var registration = ClientRegistration.withRegistrationId("catalog")
                .clientId(clientId).clientSecret(clientSecret)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .tokenUri(tokenUri).scope(scope).build();
        var registrations = new InMemoryClientRegistrationRepository(registration);
        clients = new InMemoryOAuth2AuthorizedClientService(registrations);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        var tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder().requestFactory(factory)
                .configureMessageConverters(converters -> converters.disableDefaults()
                    .addCustomConverter(new FormHttpMessageConverter())
                    .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        var provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient);
        manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(provider);
    }

    public synchronized String token() {
        if (manager == null) throw new CatalogUnavailableException("Falta configurar la identidad de servicio de Orders");
        try {
            var client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("catalog")
                    .principal("pedidos360-orders").build());
            if (client == null) throw new CatalogUnavailableException();
            return client.getAccessToken().getTokenValue();
        } catch (OAuth2AuthorizationException exception) {
            throw new CatalogUnavailableException();
        }
    }

    public synchronized void invalidate() {
        if (clients != null) clients.removeAuthorizedClient("catalog", "pedidos360-orders");
    }
}
