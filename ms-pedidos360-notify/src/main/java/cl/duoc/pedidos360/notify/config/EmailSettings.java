package cl.duoc.pedidos360.notify.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("notify.email")
public record EmailSettings(@NotBlank @Email String from, @NotBlank @Email String recipient) {}
