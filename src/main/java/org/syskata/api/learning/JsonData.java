package org.syskata.api.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.syskata.api.foundation.ApiFailure;

public final class JsonData {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private JsonData() {}

  public static JsonNode parse(String text) {
    try {
      JsonNode node = MAPPER.readTree(text);
      if (node == null || node.isMissingNode()) throw new ApiFailure(400, "invalid_request");
      return node;
    } catch (JsonProcessingException failure) {
      throw new ApiFailure(400, "invalid_request");
    }
  }

  public static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  public static JsonNode tree(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static List<String> strings(JsonNode node) {
    List<String> values = new ArrayList<>();
    node.forEach(value -> values.add(value.asText()));
    return values;
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  public static String requestHash(JsonNode body) {
    return hash(canonical(body).toString().getBytes(StandardCharsets.UTF_8));
  }

  private static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      var sorted = new TreeMap<String, JsonNode>();
      node.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
      return tree(sorted);
    }
    if (node.isArray()) {
      List<JsonNode> values = new ArrayList<>();
      node.forEach(value -> values.add(canonical(value)));
      return tree(values);
    }
    return node;
  }
}
