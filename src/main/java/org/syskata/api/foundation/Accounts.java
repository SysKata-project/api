package org.syskata.api.foundation;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

@ApplicationScoped
public class Accounts {
  private final AgroalDataSource database;
  private final AgroalDataSource evidence;

  public Accounts(AgroalDataSource database, @DataSource("evidence") AgroalDataSource evidence) {
    this.database = database;
    this.evidence = evidence;
  }

  public UUID active(String issuer, String subject) throws SQLException {
    if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
      throw new ApiFailure(401, "unauthenticated");
    }
    ensureNotDeleted(issuer, subject);
    try (Connection connection = database.getConnection()) {
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO learners(id,issuer,subject) VALUES (?,?,?) ON CONFLICT (issuer,subject) DO NOTHING")) {
        insert.setObject(1, UUID.randomUUID());
        insert.setString(2, issuer);
        insert.setString(3, subject);
        insert.executeUpdate();
      }
      try (var query =
          connection.prepareStatement(
              "SELECT id,disabled_at FROM learners WHERE issuer=? AND subject=?")) {
        query.setString(1, issuer);
        query.setString(2, subject);
        try (var rows = query.executeQuery()) {
          if (!rows.next() || rows.getObject(2) != null) {
            throw new ApiFailure(403, "account_disabled");
          }
          return rows.getObject(1, UUID.class);
        }
      }
    }
  }

  public void ensureNotDeleted(String issuer, String subject) throws SQLException {
    try (Connection connection = evidence.getConnection();
        var query =
            connection.prepareStatement(
                "SELECT 1 FROM deletion_requests WHERE issuer=? AND subject=?")) {
      query.setString(1, issuer);
      query.setString(2, subject);
      try (var rows = query.executeQuery()) {
        if (rows.next()) {
          throw new ApiFailure(403, "account_disabled");
        }
      }
    }
  }

  public void requireSessionOwner(UUID session, UUID learner) throws SQLException {
    try (Connection connection = database.getConnection();
        var query =
            connection.prepareStatement(
                "SELECT 1 FROM learning_sessions WHERE id=? AND learner_id=?")) {
      query.setObject(1, session);
      query.setObject(2, learner);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) {
          throw new ApiFailure(404, "not_found");
        }
      }
    }
  }
}
