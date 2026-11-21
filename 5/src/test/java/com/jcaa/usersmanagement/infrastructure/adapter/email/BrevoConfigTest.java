package com.jcaa.usersmanagement.infrastructure.adapter.email;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BrevoConfig")
class BrevoConfigTest {

  @Test
  @DisplayName("normaliza la URL y valida una configuracion correcta")
  void shouldAcceptValidConfiguration() {
    final BrevoConfig config =
        assertDoesNotThrow(
            () ->
                config(
                    "https://api.brevo.test/",
                    "xkeysib-valid",
                    "Sender@Example.com",
                    Duration.ofSeconds(2),
                    3,
                    2.0,
                    50,
                    10,
                    5));

    assertAll(
        () -> assertEquals("https://api.brevo.test", config.baseUrl()),
        () -> assertEquals("sender@example.com", config.fromAddress()));
  }

  @Test
  @DisplayName("rechaza claves vacias y claves SMTP")
  void shouldRejectBlankOrSmtpKeys() {
    assertAll(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> config("", "xkeysib-valid", "sender@example.com")),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    config(
                        "https://api.brevo.com",
                        "xsmtpsib-not-rest",
                        "sender@example.com")));
  }

  @Test
  @DisplayName("rechaza URLs y remitentes invalidos")
  void shouldRejectInvalidUrlAndSender() {
    assertAll(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> config("ftp://api.brevo.com", "xkeysib-valid", "sender@example.com")),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> config("not-a-url", "xkeysib-valid", "sender@example.com")),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> config("https://api.brevo.com", "xkeysib-valid", "not-an-email")));
  }

  @Test
  @DisplayName("rechaza parametros de resiliencia fuera de rango")
  void shouldRejectInvalidResilienceParameters() {
    assertAll(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    config(
                        "https://api.brevo.com",
                        "xkeysib-valid",
                        "sender@example.com",
                        Duration.ZERO,
                        3,
                        2.0,
                        50,
                        10,
                        5)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    config(
                        "https://api.brevo.com",
                        "xkeysib-valid",
                        "sender@example.com",
                        Duration.ofSeconds(1),
                        0,
                        2.0,
                        50,
                        10,
                        5)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    config(
                        "https://api.brevo.com",
                        "xkeysib-valid",
                        "sender@example.com",
                        Duration.ofSeconds(1),
                        3,
                        1.0,
                        50,
                        10,
                        5)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    config(
                        "https://api.brevo.com",
                        "xkeysib-valid",
                        "sender@example.com",
                        Duration.ofSeconds(1),
                        3,
                        2.0,
                        101,
                        10,
                        5)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    config(
                        "https://api.brevo.com",
                        "xkeysib-valid",
                        "sender@example.com",
                        Duration.ofSeconds(1),
                        3,
                        2.0,
                        50,
                        0,
                        0)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    config(
                        "https://api.brevo.com",
                        "xkeysib-valid",
                        "sender@example.com",
                        Duration.ofSeconds(1),
                        3,
                        2.0,
                        50,
                        2,
                        3)));
  }

  private static BrevoConfig config(
      final String baseUrl, final String apiKey, final String fromAddress) {
    return config(
        baseUrl,
        apiKey,
        fromAddress,
        Duration.ofSeconds(1),
        3,
        2.0,
        50,
        10,
        5);
  }

  private static BrevoConfig config(
      final String baseUrl,
      final String apiKey,
      final String fromAddress,
      final Duration connectTimeout,
      final int attempts,
      final double multiplier,
      final int failureThreshold,
      final int windowSize,
      final int minimumCalls) {
    return new BrevoConfig(
        baseUrl,
        apiKey,
        fromAddress,
        "Gestion de Usuarios",
        connectTimeout,
        Duration.ofSeconds(1),
        attempts,
        Duration.ofMillis(10),
        multiplier,
        failureThreshold,
        windowSize,
        minimumCalls,
        Duration.ofSeconds(1));
  }
}
