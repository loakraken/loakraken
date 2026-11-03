package com.loakraken.common.api;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ResponseContracts {
  private ResponseContracts() {}

  @SuppressWarnings("rawtypes")
  public static void apply(Class<?> root, Map<String, Schema> schemas) {
    apply(root, schemas, new HashSet<>(), root.getPackageName());
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // Swagger 모델 API가 raw Schema 맵을 반환한다.
  private static void apply(
      Type type, Map<String, Schema> schemas, Set<Class<?>> visited, String packageName) {
    if (type instanceof ParameterizedType parameterized) {
      for (var argument : parameterized.getActualTypeArguments())
        apply(argument, schemas, visited, packageName);
      return;
    }
    if (!(type instanceof Class<?> recordType)
        || !recordType.isRecord()
        || !recordType.getPackageName().equals(packageName)
        || !visited.add(recordType)) return;
    var schema = schemas.get(recordType.getSimpleName());
    if (schema == null)
      throw new IllegalStateException("응답 스키마를 찾을 수 없습니다: " + recordType.getSimpleName());
    var required = new ArrayList<String>();
    for (var component : recordType.getRecordComponents()) {
      if (!component.isAnnotationPresent(OptionalResponse.class)) required.add(component.getName());
      if (component.isAnnotationPresent(NullableResponse.class)) {
        Schema<?> property = (Schema<?>) schema.getProperties().get(component.getName());
        if (property == null)
          throw new IllegalStateException("응답 필드 스키마를 찾을 수 없습니다: " + component.getName());
        // 여러 응답이 같은 안쪽 record(공통 meta 등)를 공유하면 이 함수가 그 스키마를 다시 지난다. 한 번만 감싼다.
        if (!nullable(property)) {
          var nullType = new Schema<>();
          nullType.setTypes(Set.of("null"));
          // $ref와 type:null의 동시 선언은 양쪽을 모두 만족할 수 없다. anyOf로 명시한다.
          var nullable = new ComposedSchema();
          nullable.addAnyOfItem(property);
          nullable.addAnyOfItem(nullType);
          schema.addProperty(component.getName(), nullable);
        }
      }
      apply(component.getGenericType(), schemas, visited, packageName);
    }
    // 직렬화 정책상 응답 record의 필드는 null이어도 생략하지 않는다.
    schema.setRequired(required);
  }

  /** 이미 anyOf에 type:null이 있는(감싼) 스키마인가. */
  @SuppressWarnings("rawtypes")
  private static boolean nullable(Schema<?> property) {
    var anyOf = property.getAnyOf();
    if (anyOf == null) return false;
    for (Schema item : anyOf)
      if (item.getTypes() != null && item.getTypes().contains("null")) return true;
    return false;
  }
}
