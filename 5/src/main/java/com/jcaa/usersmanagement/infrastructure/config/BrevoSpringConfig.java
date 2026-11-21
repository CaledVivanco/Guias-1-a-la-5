package com.jcaa.usersmanagement.infrastructure.config;

import com.jcaa.usersmanagement.infrastructure.adapter.email.BrevoConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class BrevoSpringConfig {

  @Bean
  public BrevoConfig brevoConfig(
      @Value("${brevo.base-url}") final String baseUrl,
      @Value("${brevo.local.api-key:${brevo.api-key:}}") final String apiKey,
      @Value("${brevo.local.from.address:${brevo.from.address:}}") final String fromAddress,
      @Value("${brevo.local.from.name:${brevo.from.name:Gestion de Usuarios}}")
          final String fromName,
      @Value("${brevo.timeout.connect}") final Duration connectTimeout,
      @Value("${brevo.timeout.read}") final Duration readTimeout,
      @Value("${brevo.retry.max-attempts}") final int retryMaxAttempts,
      @Value("${brevo.retry.initial-delay}") final Duration retryInitialDelay,
      @Value("${brevo.retry.multiplier}") final double retryMultiplier,
      @Value("${brevo.circuit-breaker.failure-rate-threshold}") final int failureThreshold,
      @Value("${brevo.circuit-breaker.sliding-window-size}") final int slidingWindowSize,
      @Value("${brevo.circuit-breaker.minimum-calls}") final int minimumCalls,
      @Value("${brevo.circuit-breaker.open-duration}") final Duration openDuration) {
    final BrevoConfig config =
        new BrevoConfig(
            baseUrl,
            apiKey,
            fromAddress,
            fromName,
            connectTimeout,
            readTimeout,
            retryMaxAttempts,
            retryInitialDelay,
            retryMultiplier,
            failureThreshold,
            slidingWindowSize,
            minimumCalls,
            openDuration);
    log.info(
        "[BrevoSpringConfig] configuracion cargada. apiKeyFingerprint={} fromAddress={} "
            + "timeouts={}/{} retryAttempts={}",
        fingerprint(apiKey),
        config.fromAddress(),
        config.connectTimeout(),
        config.readTimeout(),
        config.retryMaxAttempts());
    return config;
  }

  @Bean
  public RestClient brevoRestClient(final BrevoConfig config) {
    final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(config.connectTimeout());
    requestFactory.setReadTimeout(config.readTimeout());
    return RestClient.builder()
        .baseUrl(config.baseUrl())
        .requestFactory(requestFactory)
        .defaultHeader("api-key", config.apiKey())
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }

  static String fingerprint(final String value) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 no esta disponible.", exception);
    }
  }
}
