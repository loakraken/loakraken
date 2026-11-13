package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAPI 스키마 이름은 Java 단순 이름이라, 이름이 같은 서로 다른 응답·요청 레코드가 있으면 한쪽 모양으로 덮인다(LevelCountResponse 충돌, 프론트
 * 11-13). 컨트롤러가 주고받는 레코드를 따라가며, 같은 스키마 이름(@Schema(name) 또는 단순 이름)에 서로 다른 타입이 둘 이상이면 실패한다. enum은
 * 인라인이라 빼고 센다.
 */
class OpenApiSchemaNameTest {

  @Test
  void 컨트롤러가_주고받는_레코드의_스키마_이름은_겹치지_않는다() throws Exception {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    var seen = new HashSet<Class<?>>();
    for (var bean : scanner.findCandidateComponents("com.loakraken")) {
      var controller = Class.forName(bean.getBeanClassName());
      for (var method : controller.getDeclaredMethods()) {
        if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
        walk(method.getGenericReturnType(), seen);
        for (var parameter : method.getParameters())
          if (parameter.isAnnotationPresent(RequestBody.class)
              || parameter.isAnnotationPresent(
                  org.springdoc.core.annotations.ParameterObject.class))
            walk(parameter.getParameterizedType(), seen);
      }
    }
    Map<String, Set<String>> byName = new TreeMap<>();
    for (var type : seen)
      byName.computeIfAbsent(schemaName(type), key -> new TreeSet<>()).add(type.getName());
    var collisions = new ArrayList<String>();
    byName.forEach(
        (name, types) -> {
          if (types.size() > 1) collisions.add(name + " = " + types);
        });
    assertThat(seen).as("컨트롤러에서 레코드를 찾았는가").isNotEmpty();
    assertThat(collisions).as("같은 OpenAPI 스키마 이름의 서로 다른 타입").isEmpty();
  }

  private static String schemaName(Class<?> type) {
    var schema = type.getAnnotation(Schema.class);
    return schema != null && !schema.name().isBlank() ? schema.name() : type.getSimpleName();
  }

  private static void walk(Type type, Set<Class<?>> seen) {
    if (type instanceof ParameterizedType parameterized) {
      walk(parameterized.getRawType(), seen);
      for (var argument : parameterized.getActualTypeArguments()) walk(argument, seen);
      return;
    }
    if (type instanceof GenericArrayType array) {
      walk(array.getGenericComponentType(), seen);
      return;
    }
    if (type instanceof WildcardType wildcard) {
      for (var bound : wildcard.getUpperBounds()) walk(bound, seen);
      return;
    }
    if (!(type instanceof Class<?> raw)) return;
    if (raw.isArray()) {
      walk(raw.getComponentType(), seen);
      return;
    }
    if (raw.isPrimitive() || raw.isEnum() || !raw.getName().startsWith("com.loakraken.")) return;
    if (!seen.add(raw)) return;
    if (raw.isRecord())
      for (var component : raw.getRecordComponents()) walk(component.getGenericType(), seen);
  }
}
