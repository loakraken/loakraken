package com.loakraken.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseContractsTest {
  record Inner(@NullableResponse String value) {}

  record First(Inner inner) {}

  record Second(Inner inner) {}

  @Test
  @SuppressWarnings("rawtypes")
  void 공유한_안쪽_record의_nullable은_한_번만_감싼다() {
    Map<String, Schema> schemas = new HashMap<>();
    schemas.put("Inner", new ObjectSchema().addProperty("value", new StringSchema()));
    schemas.put("First", new ObjectSchema().addProperty("inner", new Schema<>().$ref("Inner")));
    schemas.put("Second", new ObjectSchema().addProperty("inner", new Schema<>().$ref("Inner")));

    ResponseContracts.apply(First.class, schemas);
    ResponseContracts.apply(Second.class, schemas);

    Schema<?> value = (Schema<?>) schemas.get("Inner").getProperties().get("value");
    assertThat(value.getAnyOf()).hasSize(2);
    assertThat(value.getAnyOf().get(0)).isInstanceOf(StringSchema.class);
    assertThat(value.getAnyOf().get(1).getTypes()).containsExactly("null");
    assertThat(schemas.get("Second").getRequired()).containsExactly("inner");
  }
}
