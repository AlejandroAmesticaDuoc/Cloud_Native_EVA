package cl.duoc.pedidos360.orders.security.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class TestJwtIssuer implements AutoCloseable {

    public static final String AUDIENCE =
            "pedidos360-bff-test";

    private final RSAKey signingKey;
    private final HttpServer server;

    public TestJwtIssuer() {
        try {
            signingKey = new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();

            server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );

            String publicKeys = new JWKSet(
                    signingKey.toPublicJWK()
            ).toString();

            String metadata = """
                    {
                      "issuer": "%s",
                      "jwks_uri": "%s/jwks"
                    }
                    """.formatted(issuerUri(), issuerUri());

            server.createContext(
                    "/.well-known/openid-configuration",
                    exchange -> respondJson(exchange, metadata)
            );

            server.createContext(
                    "/jwks",
                    exchange -> respondJson(exchange, publicKeys)
            );

            server.start();
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException(
                    "No se pudo iniciar el emisor JWT de prueba",
                    exception
            );
        }
    }

    public String issuerUri() {
        return "http://127.0.0.1:"
                + server.getAddress().getPort();
    }

    public JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();

        return new JWTClaimsSet.Builder()
                .issuer(issuerUri())
                .audience(AUDIENCE)
                .subject("subject-cliente")
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("oid", "cliente-prueba")
                .claim("tid", "tenant-prueba")
                .claim(
                        "preferred_username",
                        "cliente@example.test"
                )
                .claim("name", "Cliente de prueba")
                .claim("scp", "pedidos360.access")
                .claim("roles", List.of("CLIENTE"));
    }

    public String sign(JWTClaimsSet claims)
            throws JOSEException {

        JWSHeader header = new JWSHeader.Builder(
                JWSAlgorithm.RS256
        )
                .type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID())
                .build();

        SignedJWT token = new SignedJWT(header, claims);

        token.sign(new RSASSASigner(signingKey));

        return token.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void respondJson(
            HttpExchange exchange,
            String json) throws IOException {

        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        try {
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );

            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
