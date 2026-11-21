package com.jcaa.usersmanagement.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jcaa.usersmanagement.infrastructure.adapter.email.BrevoConfig;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BrevoSpringConfig")
class BrevoSpringConfigTest {

  private final BrevoSpringConfig springConfig = new BrevoSpringConfig();

  @Test
  @DisplayName("crea la configuracion y el cliente RestClient")
  void shouldCreateConfigurationBeans() {
    final BrevoConfig config = newConfig();

    assertNotNull(springConfig.brevoRestClient(config));
    assertEquals(12, BrevoSpringConfig.fingerprint("xkeysib-test").length());
  }

  @Test
  @DisplayName("falla al construir una configuracion incompleta")
  void shouldFailForIncompleteConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            springConfig.brevoConfig(
                "https://api.brevo.com",
                "",
                "sender@example.com",
                "Sistema",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                3,
                Duration.ofMillis(1),
                2.0,
                50,
                10,
                5,
                Duration.ofSeconds(1)));
  }

  private BrevoConfig newConfig() {
    return springConfig.brevoConfig(
        "https://api.brevo.com",
        "xkeysib-test",
        "sender@example.com",
        "Sistema",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        3,
        Duration.ofMillis(1),
        2.0,
        50,
        10,
        5,
        Duration.ofSeconds(1));
  }
}
