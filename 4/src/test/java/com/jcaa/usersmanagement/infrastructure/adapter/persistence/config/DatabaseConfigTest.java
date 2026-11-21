package com.jcaa.usersmanagement.infrastructure.adapter.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseConfigTest {

  private static final String HOST = "gateway01.example.prod.aws.tidbcloud.com";
  private static final int PORT = 4000;
  private static final String DATABASE = "crud_usuarios";
  private static final String USERNAME = "cluster-prefix.root";
  private static final String PASSWORD = "secret";
  private static final String SSL_MODE = "VERIFY_IDENTITY";

  @Test
  void shouldBuildJdbcUrlWithConfiguredSslMode() {
    // Arrange
    final DatabaseConfig config =
        new DatabaseConfig(HOST, PORT, DATABASE, USERNAME, PASSWORD, SSL_MODE);

    // Act
    final String jdbcUrl = config.buildJdbcUrl();

    // Assert
    assertThat(jdbcUrl)
        .isEqualTo(
            "jdbc:mysql://gateway01.example.prod.aws.tidbcloud.com:4000/crud_usuarios"
                + "?sslMode=VERIFY_IDENTITY&serverTimezone=UTC&allowPublicKeyRetrieval=true");
  }
}
