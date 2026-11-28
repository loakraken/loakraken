package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 응답 견본의 null이 OpenAPI 문서에서 null을 허용하는지 본다(프론트2 11-22: 견본이 생성 문서 검증을 통과해야 한다). 객체는 $ref·anyOf를 따라가고
 * 배열은 items를 따라간다. null 칸의 스키마는 anyOf 안에 type null이 있어야 한다(ResponseContracts의 @NullableResponse
 * 감싸기).
 */
final class OpenApiNulls {
  private OpenApiNulls() {}

  static JsonNode document(int port) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return new JsonMapper().readTree(response.body());
  }

  /** data(응답의 data)를 schemaName 스키마로 읽어 null 칸마다 null 허용을 확인한다. */
  static void assertNullsAllowed(JsonNode document, String schemaName, JsonNode data) {
    walk(document, schema(document, schemaName), data, schemaName);
  }

  private static JsonNode schema(JsonNode document, String name) {
    var schema = document.at("/components/schemas/" + name);
    assertThat(schema.isMissingNode()).as("스키마 " + name).isFalse();
    return schema;
  }

  private static JsonNode resolve(JsonNode document, JsonNode schema) {
    if (schema.has("$ref")) {
      var ref = schema.path("$ref").asString();
      return schema(document, ref.substring(ref.lastIndexOf('/') + 1));
    }
    if (schema.has("anyOf"))
      for (var item : schema.path("anyOf"))
        if (!item.path("type").asString("").equals("null")
            && !(item.path("types").isArray() && item.path("types").toString().contains("null")))
          return resolve(document, item);
    return schema;
  }

  private static boolean allowsNull(JsonNode schema) {
    if (schema.path("nullable").asBoolean(false)) return true;
    if (schema.path("type").isArray() && schema.path("type").toString().contains("\"null\""))
      return true;
    for (var item : schema.path("anyOf"))
      if ("null".equals(item.path("type").asString(""))
          || item.path("type").toString().contains("\"null\"")) return true;
    return false;
  }

  private static void walk(JsonNode document, JsonNode schema, JsonNode value, String path) {
    var resolved = resolve(document, schema);
    if (value.isObject()) {
      var properties = resolved.path("properties");
      for (var entry : value.properties()) {
        var property = properties.path(entry.getKey());
        assertThat(property.isMissingNode())
            .as("문서에 없는 칸 " + path + "." + entry.getKey())
            .isFalse();
        if (entry.getValue().isNull())
          assertThat(allowsNull(property))
              .as("null인데 문서가 null을 허용하지 않음: " + path + "." + entry.getKey())
              .isTrue();
        else walk(document, property, entry.getValue(), path + "." + entry.getKey());
      }
    } else if (value.isArray()) {
      var items = resolved.path("items");
      int i = 0;
      for (var item : value) {
        if (!item.isNull()) walk(document, items, item, path + "[" + i + "]");
        i++;
      }
    }
  }
}
