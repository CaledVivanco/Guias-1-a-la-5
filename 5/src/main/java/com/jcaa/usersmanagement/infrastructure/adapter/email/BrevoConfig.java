package com.jcaa.usersmanagement.infrastructure.adapter.email;

import com.jcaa.usersmanagement.domain.valueobject.UserEmail;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

public record BrevoConfig(
    String baseUrl,
    String apiKey,
    String fromAddress,
    String fromName,
    Duration connectTimeout,
    Duration readTimeout,
    int retryMaxAttempts,
    Duration retryInitialDelay,
    double retryMultiplier,
    int circuitFailureThreshold,
    int circuitSlidingWindowSize,
    int circuitMinimumCalls,
    Duration circuitOpenDuration) {

  private static final String REST_API_KEY_PREFIX = "xkeysib-";
  private static final int MAX_FAILURE_THRESHOLD = 100;

  public BrevoConfig {
    baseUrl = validateBaseUrl(baseUrl);
    apiKey = requireText(apiKey, "brevo.api-key / BREVO_API_KEY");
    fromAddress = validateEmail(fromAddress);
    fromName = requireText(fromName, "brevo.from.name / BREVO_FROM_NAME");

    if (!apiKey.startsWith(REST_API_KEY_PREFIX)) {
      throw new IllegalArgumentException(
          "BREVO_API_KEY debe ser una clave REST de Brevo con prefijo 'xkeysib-'.");
    }

    requirePositive(connectTimeout, "brevo.timeout.connect");
    requirePositive(readTimeout, "brevo.timeout.read");
    requirePositive(retryInitialDelay, "brevo.retry.initial-delay");
    requirePositive(circuitOpenDuration, "brevo.circuit-breaker.open-duration");

    if (retryMaxAttempts < 1) {
      throw new IllegalArgumentException("brevo.retry.max-attempts debe ser mayor o igual a 1.");
    }
    if (retryMultiplier <= 1.0) {
      throw new IllegalArgumentException("brevo.retry.multiplier debe ser mayor que 1.0.");
    }
    if (circuitFailureThreshold < 1 || circuitFailureThreshold > MAX_FAILURE_THRESHOLD) {
      throw new IllegalArgumentException(
          "brevo.circuit-breaker.failure-rate-threshold debe estar entre 1 y 100.");
    }
    if (circuitSlidingWindowSize < 1) {
      throw new IllegalArgumentException(
          "brevo.circuit-breaker.sliding-window-size debe ser mayor o igual a 1.");
    }
    if (circuitMinimumCalls < 1 || circuitMinimumCalls > circuitSlidingWindowSize) {
      throw new IllegalArgumentException(
          "brevo.circuit-breaker.minimum-calls debe estar entre 1 y el tamano de la ventana.");
    }
  }

  private static String validateBaseUrl(final String value) {
    final String normalized = requireText(value, "brevo.base-url").replaceAll("/+$", "");
    try {
      final URI uri = new URI(normalized);
      final String scheme =
          uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
        throw new IllegalArgumentException(
            "brevo.base-url debe ser una URL HTTP(S) absoluta.");
      }
      return normalized;
    } catch (final URISyntaxException exception) {
      throw new IllegalArgumentException("brevo.base-url no es una URL valida.", exception);
    }
  }

  private static String validateEmail(final String value) {
    final String normalized = requireText(value, "brevo.from.address / BREVO_FROM_ADDRESS");
    try {
      return new UserEmail(normalized).value();
    } catch (final RuntimeException exception) {
      throw new IllegalArgumentException(
          "brevo.from.address / BREVO_FROM_ADDRESS no es un correo valido.", exception);
    }
  }

  private static String requireText(final String value, final String propertyName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "La configuracion requerida '" + propertyName + "' no puede estar vacia.");
    }
    return value.trim();
  }

  private static void requirePositive(final Duration value, final String propertyName) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "La configuracion '" + propertyName + "' debe ser una duracion positiva.");
    }
  }
}
