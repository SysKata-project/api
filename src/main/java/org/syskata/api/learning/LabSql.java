package org.syskata.api.learning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class LabSql {
  private LabSql() {}

  static PreparedStatement query(Connection c, String sql, Object... values) throws SQLException {
    PreparedStatement q = c.prepareStatement(sql);
    try {
      for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
      return q;
    } catch (SQLException failure) {
      q.close();
      throw failure;
    }
  }

  static void update(Connection c, String sql, Object... values) throws SQLException {
    try (var q = query(c, sql, values)) {
      q.executeUpdate();
    }
  }
}
