package com.jcaa.usersmanagement.infrastructure.adapter.email;

import com.jcaa.usersmanagement.application.port.out.EmailSenderPort;
import com.jcaa.usersmanagement.domain.exception.EmailSenderException;
import com.jcaa.usersmanagement.domain.model.EmailDestinationModel;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class BrevoEmailSenderAdapter implements EmailSenderPort {

  static final String IDEMPOTENCY_HEADER = "IdempotencyKey";
  private static final String SEND_EMAIL_PATH = "/v3/smtp/email";
  private static final String RETRY_NAME = "brevo-email-retry";
  private static final String CIRCUIT_BREAKER_NAME = "brevo-email-circuit-breaker";

  private final BrevoConfig config;
  private final RestClient restClient;
  private final Retry retry;
  private final CircuitBreaker circuitBreaker;

  public BrevoEmailSenderAdapter(final BrevoConfig config, final RestClient brevoRestClient) {
    this.config = Objects.requireNonNull(config, "config");
    this.restClient = Objects.requireNonNull(brevoRestClient, "brevoRestClient");
    this.retry = buildRetry(config);
    this.circuitBreaker = buildCircuitBreaker(config);
    registerObservability();
  }

  @Override
  public void send(final EmailDestinationModel destination) {
    Objects.requireNonNull(destination, "destination");
    final BrevoEmailRequest request = buildRequest(destination, UUID.randomUUID().toString());
    final Runnable callCircuitBreaker =
        CircuitBreaker.decorateRunnable(circuitBreaker, () -> sendRequest(request));
    final Runnable protectedRequest = Retry.decorateRunnable(retry, callCircuitBreaker);

    try {
      protectedRequest.run();
      log.info(
          "[BrevoEmailSenderAdapter] correo aceptado por Brevo para {}.",
          destination.getDestinationEmail());
    } catch (final CallNotPermittedException exception) {
      throw EmailSenderException.becauseApiFailed(
          destination.getDestinationEmail(), "circuit breaker abierto", exception);
    } catch (final RestClientResponseException exception) {
      throw EmailSenderException.becauseApiFailed(
          destination.getDestinationEmail(),
          "HTTP " + exception.getStatusCode().value(),
          exception);
    } catch (final RestClientException exception) {
      throw EmailSenderException.becauseApiFailed(
          destination.getDestinationEmail(), exception.getClass().getSimpleName(), exception);
    }
  }

  private void sendRequest(final BrevoEmailRequest request) {
    restClient.post().uri(SEND_EMAIL_PATH).body(request).retrieve().toBodilessEntity();
  }

  private BrevoEmailRequest buildRequest(
      final EmailDestinationModel destination, final String idempotencyKey) {
    return new BrevoEmailRequest(
        new BrevoEmailRequest.Contact(config.fromAddress(), config.fromName()),
        List.of(
            new BrevoEmailRequest.Contact(
                destination.getDestinationEmail(), destination.getDestinationName())),
        destination.getSubject(),
        destination.getBody(),
        Map.of(IDEMPOTENCY_HEADER, idempotencyKey));
  }

  private static Retry buildRetry(final BrevoConfig config) {
    final IntervalFunction exponentialBackoff =
        IntervalFunction.ofExponentialBackoff(
            config.retryInitialDelay().toMillis(), config.retryMultiplier());
    final RetryConfig retryConfig =
        RetryConfig.custom()
            .maxAttempts(config.retryMaxAttempts())
            .intervalFunction(exponentialBackoff)
            .retryOnException(BrevoEmailSenderAdapter::isTransientFailure)
            .build();
    return Retry.of(RETRY_NAME, retryConfig);
  }

  private static CircuitBreaker buildCircuitBreaker(final BrevoConfig config) {
    final Predicate<Throwable> transientFailure = BrevoEmailSenderAdapter::isTransientFailure;
    final CircuitBreakerConfig circuitConfig =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(config.circuitFailureThreshold())
            .slidingWindowSize(config.circuitSlidingWindowSize())
            .minimumNumberOfCalls(config.circuitMinimumCalls())
            .waitDurationInOpenState(config.circuitOpenDuration())
            .recordException(transientFailure)
            .build();
    return CircuitBreaker.of(CIRCUIT_BREAKER_NAME, circuitConfig);
  }

  private void registerObservability() {
    retry
        .getEventPublisher()
        .onRetry(
            event ->
                log.warn(
                    "[BrevoEmailSenderAdapter] reintento {} por {}.",
                    event.getNumberOfRetryAttempts(),
                    exceptionType(event.getLastThrowable())));
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                log.warn(
                    "[BrevoEmailSenderAdapter] circuit breaker {} -> {}.",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));
  }

  private static boolean isTransientFailure(final Throwable exception) {
    if (exception instanceof ResourceAccessException) {
      return true;
    }
    if (exception instanceof RestClientResponseException responseException) {
      final int status = responseException.getStatusCode().value();
      return status == 408 || status == 429 || status >= 500;
    }
    return false;
  }

  private static String exceptionType(final Throwable exception) {
    return Objects.isNull(exception) ? "causa desconocida" : exception.getClass().getSimpleName();
  }
}
