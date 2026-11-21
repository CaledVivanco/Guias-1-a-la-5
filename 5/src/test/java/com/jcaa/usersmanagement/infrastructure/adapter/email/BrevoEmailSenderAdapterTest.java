package com.jcaa.usersmanagement.infrastructure.adapter.email;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcaa.usersmanagement.domain.exception.EmailSenderException;
import com.jcaa.usersmanagement.domain.model.EmailDestinationModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("BrevoEmailSenderAdapter")
class BrevoEmailSenderAdapterTest {

  private static final String API_KEY = "xkeysib-test-api-key";
  private static final String DESTINATION = "john@example.com";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private RestClient.Builder restClientBuilder;
  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    restClientBuilder =
        RestClient.builder()
            .baseUrl("https://api.brevo.test")
            .defaultHeader("api-key", API_KEY)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
    server = MockRestServiceServer.bindTo(restClientBuilder).build();
  }

  @Test
  @DisplayName("envia el contrato JSON esperado con IdempotencyKey")
  void shouldSendExpectedPayload() {
    server
        .expect(once(), requestTo("https://api.brevo.test/v3/smtp/email"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("api-key", API_KEY))
        .andExpect(jsonPath("$.sender.email").value("noreply@example.com"))
        .andExpect(jsonPath("$.sender.name").value("App Notifications"))
        .andExpect(jsonPath("$.to[0].email").value(DESTINATION))
        .andExpect(jsonPath("$.to[0].name").value("John Doe"))
        .andExpect(jsonPath("$.subject").value("Account created"))
        .andExpect(jsonPath("$.htmlContent").value("<html>Welcome</html>"))
        .andExpect(jsonPath("$.headers.IdempotencyKey").isNotEmpty())
        .andRespond(withSuccess("{\"messageId\":\"message-1\"}", MediaType.APPLICATION_JSON));

    assertDoesNotThrow(() -> adapter(config(3, 5)).send(destination()));
    server.verify();
  }

  @Test
  @DisplayName("reintenta 5xx y 429 y luego envia")
  void shouldRetryTransientFailures() {
    final List<String> idempotencyKeys = new ArrayList<>();
    server
        .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
        .andExpect(captureIdempotencyKey(idempotencyKeys))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
        .andExpect(captureIdempotencyKey(idempotencyKeys))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    server
        .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
        .andExpect(captureIdempotencyKey(idempotencyKeys))
        .andRespond(withSuccess());

    assertDoesNotThrow(() -> adapter(config(3, 5)).send(destination()));
    assertEquals(3, idempotencyKeys.size());
    assertEquals(1, idempotencyKeys.stream().distinct().count());
    server.verify();
  }

  @Test
  @DisplayName("no reintenta errores 4xx permanentes")
  void shouldNotRetryPermanentClientErrors() {
    server
        .expect(once(), requestTo("https://api.brevo.test/v3/smtp/email"))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));

    final EmailSenderException exception =
        assertThrows(
            EmailSenderException.class,
            () -> adapter(config(3, 5)).send(destination()));

    assertTrue(exception.getMessage().contains("HTTP 400"));
    server.verify();
  }

  @Test
  @DisplayName("abre el circuito y deja de invocar Brevo")
  void shouldOpenCircuitAfterFailureThreshold() {
    server
        .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(requestTo("https://api.brevo.test/v3/smtp/email"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    final BrevoEmailSenderAdapter adapter = adapter(config(1, 2));

    assertThrows(EmailSenderException.class, () -> adapter.send(destination()));
    assertThrows(EmailSenderException.class, () -> adapter.send(destination()));
    final EmailSenderException openCircuit =
        assertThrows(EmailSenderException.class, () -> adapter.send(destination()));

    assertTrue(openCircuit.getMessage().contains("circuit breaker abierto"));
    server.verify();
  }

  @Test
  @DisplayName("rechaza un destinatario nulo")
  void shouldRejectNullDestination() {
    assertThrows(NullPointerException.class, () -> adapter(config(1, 1)).send(null));
  }

  private BrevoEmailSenderAdapter adapter(final BrevoConfig config) {
    return new BrevoEmailSenderAdapter(config, restClientBuilder.build());
  }

  private static BrevoConfig config(final int attempts, final int minimumCalls) {
    return new BrevoConfig(
        "https://api.brevo.test",
        API_KEY,
        "noreply@example.com",
        "App Notifications",
        Duration.ofMillis(100),
        Duration.ofMillis(100),
        attempts,
        Duration.ofMillis(1),
        2.0,
        50,
        minimumCalls,
        minimumCalls,
        Duration.ofSeconds(30));
  }

  private static EmailDestinationModel destination() {
    return new EmailDestinationModel(
        DESTINATION, "John Doe", "Account created", "<html>Welcome</html>");
  }

  private static RequestMatcher captureIdempotencyKey(final List<String> keys) {
    return request -> {
      final MockClientHttpRequest mockRequest = (MockClientHttpRequest) request;
      final JsonNode body = OBJECT_MAPPER.readTree(mockRequest.getBodyAsString());
      keys.add(body.path("headers").path("IdempotencyKey").asText());
    };
  }
}
