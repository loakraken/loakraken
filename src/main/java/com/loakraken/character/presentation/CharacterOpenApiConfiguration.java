package com.loakraken.character.presentation;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CharacterOpenApiConfiguration {
  @Bean
  OpenApiCustomizer characterResponseContract() {
    return document ->
        apply(CharacterResponse.class, document.getComponents().getSchemas(), new HashSet<>());
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // Swagger 모델 API가 raw Schema 맵을 반환한다.
  private void apply(Type type, Map<String, Schema> schemas, Set<Class<?>> visited) {
    if (type instanceof ParameterizedType parameterized) {
      for (var argument : parameterized.getActualTypeArguments()) apply(argument, schemas, visited);
      return;
    }
    if (!(type instanceof Class<?> recordType)
        || !recordType.isRecord()
        || !recordType.getPackageName().equals(CharacterResponse.class.getPackageName())
        || !visited.add(recordType)) return;
    var schema = schemas.get(recordType.getSimpleName());
    if (schema == null)
      throw new IllegalStateException("응답 스키마를 찾을 수 없습니다: " + recordType.getSimpleName());
    var required = new ArrayList<String>();
    for (var component : recordType.getRecordComponents()) {
      required.add(component.getName());
      if (component.isAnnotationPresent(NullableResponse.class)) {
        Schema<?> property = (Schema<?>) schema.getProperties().get(component.getName());
        if (property == null)
          throw new IllegalStateException("응답 필드 스키마를 찾을 수 없습니다: " + component.getName());
        var nullType = new Schema<>();
        nullType.setTypes(Set.of("null"));
        // $ref와 type:null의 동시 선언은 양쪽을 모두 만족할 수 없다. anyOf로 명시한다.
        var nullable = new ComposedSchema();
        nullable.addAnyOfItem(property);
        nullable.addAnyOfItem(nullType);
        schema.addProperty(component.getName(), nullable);
      }
      apply(component.getGenericType(), schemas, visited);
    }
    // 직렬화 정책상 응답 record의 필드는 null이어도 생략하지 않는다.
    schema.setRequired(required);
  }
}
