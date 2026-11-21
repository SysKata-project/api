package org.syskata.api.learning;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class ContentCatalog {
  @Inject AgroalDataSource database;

  @ConfigProperty(name = "syskata.content.index")
  Optional<String> index;

  private final Map<String, Bundle> bundles = new HashMap<>();
  private final Map<String, Bundle> verified = new HashMap<>();
  private String active;

  /** Operator-controlled local index. Publish all validated artifacts before switching active. */
  public synchronized void reload() throws IOException, SQLException {
    if (index.isEmpty()) return;
    Path file = Path.of(index.get());
    var config = JsonData.parse(java.nio.file.Files.readString(file));
    Map<String, Bundle> next = new HashMap<>();
    for (var release : config.path("releases")) {
      String version = release.path("version").asText();
      BundleReader.require(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
      Bundle bundle =
          cached(
              file.toAbsolutePath().getParent().resolve(version),
              release.path("manifest_sha256").asText());
      BundleReader.require(bundle.version().equals(version));
      BundleReader.require(next.put(version, bundle) == null);
    }
    BundleReader.require(next.containsKey(config.path("active").asText()));
    // Keep every referenced version available after process restart, including historical sessions.
    try (var c = database.getConnection();
        var q = c.prepareStatement("SELECT DISTINCT bundle_version FROM learning_sessions");
        var rows = q.executeQuery()) {
      while (rows.next()) BundleReader.require(next.containsKey(rows.getString(1)));
    }
    for (Bundle bundle : next.values()) register(bundle);
    bundles.putAll(next);
    active = config.path("active").asText();
  }

  synchronized void activate(Bundle bundle) throws SQLException {
    register(bundle);
    bundles.put(bundle.version(), bundle);
    active = bundle.version();
  }

  private Bundle cached(Path directory, String hash) throws IOException {
    Bundle found = verified.get(hash);
    if (found != null) return found;
    Bundle bundle = load(directory, hash);
    verified.put(hash, bundle);
    return bundle;
  }

  Bundle load(Path directory, String hash) throws IOException {
    return BundleReader.read(directory, hash);
  }

  private void register(Bundle bundle) throws SQLException {
    var m = bundle.manifest();
    try (var c = database.getConnection()) {
      try (var q =
          c.prepareStatement(
              "INSERT INTO content_releases(bundle_version,schema_version,source_commit,learner_sha256,assessment_sha256) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING")) {
        q.setString(1, bundle.version());
        q.setString(2, "1.0.0");
        q.setString(3, m.path("source_commit").asText());
        q.setString(4, m.path("artifacts").path("learner.json").path("sha256").asText());
        q.setString(5, m.path("artifacts").path("assessment.json").path("sha256").asText());
        q.executeUpdate();
      }
      try (var q =
          c.prepareStatement(
              "SELECT source_commit,learner_sha256,assessment_sha256 FROM content_releases WHERE bundle_version=?")) {
        q.setString(1, bundle.version());
        try (var rows = q.executeQuery()) {
          BundleReader.require(rows.next());
          BundleReader.require(rows.getString(1).equals(m.path("source_commit").asText()));
          BundleReader.require(
              rows.getString(2)
                  .equals(m.path("artifacts").path("learner.json").path("sha256").asText()));
          BundleReader.require(
              rows.getString(3)
                  .equals(m.path("artifacts").path("assessment.json").path("sha256").asText()));
        }
      }
    }
  }

  public synchronized Bundle current() throws IOException, SQLException {
    reload();
    if (active == null) throw new ApiFailure(503, "unsupported_content");
    return bundles.get(active);
  }
}
