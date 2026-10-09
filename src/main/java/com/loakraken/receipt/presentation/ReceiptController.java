package com.loakraken.receipt.presentation;

import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.common.api.SuccessCode;
import com.loakraken.receipt.application.ReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "원정대 영수증", description = "검증된 항목 기준 원정대 환산(receipt-v1)")
class ReceiptController {
  private final ReceiptService receipts;

  ReceiptController(ReceiptService receipts) {
    this.receipts = receipts;
  }

  @GetMapping(value = "/api/v1/characters/receipt", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "원정대 영수증",
      description =
          "같은 서버 원정대의 장비 재련 기대 비용(+10→현재, 단계별 숨결·책 최소, 업화 18 이하 성장 지원, 현재 최저가)과 거래 가능 보석 시세를 더합니다. "
              + "젬·팔찌·악세는 금액에 넣지 않고 개수만 줍니다. 전투정보는 캐시를 먼저 쓰고, 한 요청에 최대 8명만 공식 API로 새로 받습니다(나머지 PENDING, 다시 요청하면 이어서). "
              + "다른 사이트와 성공률·포함 범위 가정이 달라 값이 다를 수 있습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "RECEIPT_SUCCESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "INVALID_REQUEST",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "CHARACTER_NOT_FOUND",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "429",
        description = "LOST_ARK_RATE_LIMITED",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "CHARACTER_CACHE_UNAVAILABLE·MARKET_CACHE_UNAVAILABLE·LOST_ARK_GATE_UNAVAILABLE",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
  })
  ApiResponse<ReceiptResponse> receipt(
      @Parameter(description = "원정대 캐릭터 이름")
          @RequestParam
          @NotBlank
          @Size(max = 16)
          @Pattern(regexp = "[\\p{L}\\p{N}]+")
          String characterName) {
    return ApiResponse.of(
        ReceiptSuccessCode.RECEIPT_SUCCESS,
        ReceiptResponse.from(receipts.receipt(characterName), receipts.refiningRuleVersion()));
  }

  enum ReceiptSuccessCode implements SuccessCode {
    RECEIPT_SUCCESS;

    public HttpStatus httpStatus() {
      return HttpStatus.OK;
    }

    public String code() {
      return name();
    }

    public String message() {
      return "원정대 영수증 계산에 성공했습니다.";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ReceiptOpenApiConfiguration {
    @Bean
    OpenApiCustomizer receiptResponseContract() {
      return document ->
          ResponseContracts.apply(ReceiptResponse.class, document.getComponents().getSchemas());
    }
  }
}
