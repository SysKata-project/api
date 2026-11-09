package org.syskata.api.foundation;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

public class TestKeys implements QuarkusTestResourceLifecycleManager {
  private static KeyPair keys;

  @Override
  public Map<String, String> start() {
    try {
      keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
      return Map.of(
          "quarkus.oidc.auth-server-url",
          "",
          "quarkus.oidc.discovery-enabled",
          "false",
          "quarkus.oidc.public-key",
          Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
          "quarkus.oidc.token.issuer",
          "https://identity.test/realms/syskata");
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  @Override
  public void stop() {}

  public static String token(String subject, String audience, long expiry, String type)
      throws Exception {
    String payload =
        "{\"iss\":\"https://identity.test/realms/syskata\",\"sub\":\""
            + subject
            + "\",\"aud\":\""
            + audience
            + "\",\"typ\":\""
            + type
            + "\",\"exp\":"
            + expiry
            + ",\"iat\":"
            + Instant.now().getEpochSecond()
            + "}";
    String data = encode("{\"alg\":\"RS256\"}") + "." + encode(payload);
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(keys.getPrivate());
    signer.update(data.getBytes(StandardCharsets.UTF_8));
    return data + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
