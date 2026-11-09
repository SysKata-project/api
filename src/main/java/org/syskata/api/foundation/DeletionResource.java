package org.syskata.api.foundation;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/v1/me/account-deletion")
@Authenticated
@Consumes("application/json")
@Produces("application/json")
public class DeletionResource {
  @Inject
  @DataSource("evidence")
  AgroalDataSource evidence;

  @Inject JsonWebToken token;

  @POST
  public Response delete(@HeaderParam("Idempotency-Key") String key, Map<String, Object> body)
      throws SQLException {
    if (key == null
        || !key.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        || body == null
        || body.size() != 1
        || !"delete-account".equals(body.get("confirmation"))) {
      throw new ApiFailure(400, "invalid_request");
    }
    // Commit the independent deny ledger first. All subsequent requests consult it,
    // so neither a lost response nor restoring an old application backup resurrects access.
    try (Connection connection = evidence.getConnection();
        var insert =
            connection.prepareStatement(
                "INSERT INTO deletion_requests(id,issuer,subject) VALUES (?,?,?) ON CONFLICT (issuer,subject) DO NOTHING RETURNING id,accepted_at,delete_by")) {
      insert.setObject(1, UUID.randomUUID());
      insert.setString(2, token.getIssuer());
      insert.setString(3, token.getSubject());
      try (var rows = insert.executeQuery()) {
        if (!rows.next()) {
          throw new ApiFailure(403, "account_disabled");
        }
        return Response.accepted(
                Map.of(
                    "deletion_id",
                    rows.getObject(1).toString(),
                    "status",
                    "accepted",
                    "accepted_at",
                    rows.getTimestamp(2).toInstant().toString(),
                    "active_data_delete_by",
                    rows.getTimestamp(3).toInstant().toString(),
                    "access_disabled",
                    true))
            .header("Cache-Control", "no-store")
            .build();
      }
    }
  }
}
