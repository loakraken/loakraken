package com.loakraken.common.infrastructure;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
  @Bean
  OpenAPI loakrakenOpenApi() {
    var errorSchema = new ObjectSchema();
    errorSchema.setDescription("공통 오류 응답. HTTP 상태와 업무 코드를 함께 확인합니다.");
    errorSchema.addProperty("code", new StringSchema().example("INVALID_REQUEST"));
    errorSchema.addProperty("message", new StringSchema().example("요청 값이 올바르지 않습니다."));
    var nullSchema = new Schema<>();
    nullSchema.setTypes(Set.of("null"));
    errorSchema.addProperty("data", nullSchema);
    errorSchema.setRequired(List.of("code", "message", "data"));
    return new OpenAPI()
        .info(
            new Info()
                .title("LOAKraken API")
                .version("1.0.0")
                .contact(
                    new Contact()
                        .name("LOAKraken")
                        .url("https://github.com/loakraken/loakraken/issues"))
                .description("로스트아크 캐릭터 조회 및 성장 분석 API. 실제 구현된 /api/v1 경로를 문서화합니다."))
        .servers(List.of(new Server().url("/").description("현재 API 서버")))
        .components(new Components().addSchemas("ErrorResponse", errorSchema));
  }

  /** 모든 /api/v1 작업은 클라이언트별 유입 제한(#108)으로 429 CLIENT_RATE_LIMITED와 Retry-After를 받을 수 있다. */
  @Bean
  org.springdoc.core.customizers.OpenApiCustomizer clientRateLimitResponse() {
    return openApi -> {
      if (openApi.getPaths() == null) return;
      openApi
          .getPaths()
          .forEach(
              (path, item) -> {
                if (!path.startsWith("/api/v1/")) return;
                item.readOperations()
                    .forEach(
                        operation -> {
                          var responses = operation.getResponses();
                          if (responses == null) return;
                          var existing = responses.get("429");
                          if (existing != null) {
                            if (existing.getDescription() == null
                                || !existing.getDescription().contains(CLIENT_LIMITED))
                              existing.setDescription(
                                  (existing.getDescription() == null
                                          ? ""
                                          : existing.getDescription() + " / ")
                                      + CLIENT_LIMITED_DESCRIPTION);
                            return;
                          }
                          responses.addApiResponse(
                              "429",
                              new io.swagger.v3.oas.models.responses.ApiResponse()
                                  .description(CLIENT_LIMITED_DESCRIPTION)
                                  .content(
                                      new io.swagger.v3.oas.models.media.Content()
                                          .addMediaType(
                                              "application/json",
                                              new io.swagger.v3.oas.models.media.MediaType()
                                                  .schema(
                                                      new Schema<>()
                                                          .$ref(
                                                              "#/components/schemas/ErrorResponse")))));
                        });
              });
    };
  }

  private static final String CLIENT_LIMITED = "CLIENT_RATE_LIMITED";
  private static final String CLIENT_LIMITED_DESCRIPTION =
      CLIENT_LIMITED + ": 이 클라이언트의 분당 요청·공식 호출 한도 초과. Retry-After(초) 뒤 다시 시도";
}
