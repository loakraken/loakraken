package com.loakraken.alerts.presentation;

import com.loakraken.alerts.application.AlertService;
import com.loakraken.alerts.application.AlertStore;
import com.loakraken.alerts.domain.AlertComparator;
import com.loakraken.alerts.infrastructure.AlertsEnabledCondition;
import com.loakraken.common.api.ApiResponse;
import com.loakraken.common.api.NullableResponse;
import com.loakraken.common.api.ResponseContracts;
import com.loakraken.common.api.SuccessCode;
import com.loakraken.common.lostark.ClientLimiter;
import com.loakraken.common.lostark.LostArkRequestGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시세 알림 웹 푸시(M33). 로그인 없이 브라우저별 익명 구독이다. 구독을 만들면 관리 토큰을 한 번 돌려주고, 이후 요청은 X-Alert-Token 헤더로 그 토큰을
 * 보낸다(주소에 넣지 않음). 토큰이 틀리면 구독이 없을 때와 같은 404다.
 */
@RestController
@Validated
@Conditional(AlertsEnabledCondition.class)
@Tag(
    name = "시세 알림",
    description =
        "로그인 없는 브라우저 웹 푸시 시세 알림입니다(M33). 이미 주기적으로 시세를 갱신하는 품목에만 걸 수 있고, 조건이 거짓에서 참으로 바뀔 때 보냅니다. 공식 API를 부르지 않습니다.")
public class AlertController {
  static final String TOKEN_HEADER = "X-Alert-Token";
  private static final String ERRORS =
      "400 INVALID_REQUEST·ALERT_INVALID_SUBSCRIPTION·ALERT_PUSH_NOT_DELIVERED·ALERT_ITEM_NOT_ALERTABLE·ALERT_TOO_MANY_CONDITIONS, 404 ALERT_SUBSCRIPTION_NOT_FOUND·ALERT_CONDITION_NOT_FOUND, 429 CLIENT_RATE_LIMITED, 503 ALERTS_FULL";
  private final AlertService alerts;
  private final LostArkRequestGate gate;
  private final String vapidPublicKey;

  public AlertController(
      AlertService alerts,
      LostArkRequestGate gate,
      com.loakraken.alerts.application.AlertProperties properties) {
    this.alerts = alerts;
    this.gate = gate;
    this.vapidPublicKey = properties.vapidPublicKey();
  }

  enum AlertSuccessCode implements SuccessCode {
    ALERT_SUCCESS;

    public HttpStatus httpStatus() {
      return HttpStatus.OK;
    }

    public String code() {
      return name();
    }

    public String message() {
      return "시세 알림 요청에 성공했습니다.";
    }
  }

  @GetMapping(
      value = "/api/v1/alerts/vapid-public-key",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "웹 푸시 공개 키",
      description = "브라우저 PushManager.subscribe의 applicationServerKey(base64url).")
  public ApiResponse<VapidKeyResponse> vapidPublicKey() {
    return ApiResponse.of(AlertSuccessCode.ALERT_SUCCESS, new VapidKeyResponse(vapidPublicKey));
  }

  @GetMapping(value = "/api/v1/alerts/items", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "알림을 걸 수 있는 품목", description = "최근 시세를 관측한 품목과 현재 판매 최저가입니다.")
  public ApiResponse<List<AlertItemResponse>> items() {
    return ApiResponse.of(
        AlertSuccessCode.ALERT_SUCCESS,
        alerts.items().stream().map(AlertItemResponse::from).toList());
  }

  @PostMapping(
      value = "/api/v1/alerts/subscriptions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "알림 구독 만들기",
      description =
          "브라우저 PushSubscription(endpoint·keys)을 등록하고 관리 토큰을 한 번 돌려줍니다(다시 보여 주지 않음). 같은 endpoint로 다시 등록하면 키와 토큰을 바꾸고(옛 토큰 무효) 옛 조건을 지웁니다. "
              + "등록 전에 확인 알림(본문 {\"type\":\"SUBSCRIBED\"}, 가격 알림은 type PRICE)을 보내고, 푸시 서비스가 받지 않으면 저장하지 않습니다(400 ALERT_PUSH_NOT_DELIVERED, 일시 장애면 503 ALERTS_FULL). "
              + "조건 없이 24시간 동안 쓰지 않은 구독은 지웁니다. "
              + ERRORS)
  public ApiResponse<SubscriptionResponse> subscribe(@Valid @RequestBody SubscribeRequest request) {
    gate.chargeClient(ClientLimiter.Bucket.ALERT_SUBSCRIBE);
    var subscription =
        alerts.subscribe(request.endpoint(), request.keys().p256dh(), request.keys().auth());
    return ApiResponse.of(
        AlertSuccessCode.ALERT_SUCCESS,
        new SubscriptionResponse(subscription.subscriptionId(), subscription.manageToken()));
  }

  @DeleteMapping(
      value = "/api/v1/alerts/subscriptions/{subscriptionId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "알림 구독 끄기", description = "구독과 조건을 즉시 지웁니다. " + ERRORS)
  public ApiResponse<Void> unsubscribe(
      @PathVariable UUID subscriptionId,
      @Parameter(description = "관리 토큰")
          @RequestHeader(value = TOKEN_HEADER, required = false)
          @Size(max = 128)
          String token) {
    alerts.unsubscribe(subscriptionId, token);
    return ApiResponse.of(AlertSuccessCode.ALERT_SUCCESS, null);
  }

  @GetMapping(
      value = "/api/v1/alerts/subscriptions/{subscriptionId}/conditions",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "알림 조건 목록", description = ERRORS)
  public ApiResponse<List<ConditionResponse>> conditions(
      @PathVariable UUID subscriptionId,
      @Parameter(description = "관리 토큰")
          @RequestHeader(value = TOKEN_HEADER, required = false)
          @Size(max = 128)
          String token) {
    return ApiResponse.of(
        AlertSuccessCode.ALERT_SUCCESS,
        alerts.conditions(subscriptionId, token).stream().map(ConditionResponse::from).toList());
  }

  @PostMapping(
      value = "/api/v1/alerts/subscriptions/{subscriptionId}/conditions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "알림 조건 추가",
      description =
          "그 품목의 판매 최저가가 기준 가격 이하(AT_OR_BELOW) 또는 이상(AT_OR_ABOVE)이 될 때 보냅니다. 만들 때 이미 조건에 맞으면(matchedNow=true) 보내지 않고, 조건에서 벗어났다가 다시 맞을 때 보냅니다. "
              + ERRORS)
  public ApiResponse<ConditionResponse> addCondition(
      @PathVariable UUID subscriptionId,
      @Parameter(description = "관리 토큰")
          @RequestHeader(value = TOKEN_HEADER, required = false)
          @Size(max = 128)
          String token,
      @Valid @RequestBody ConditionRequest request) {
    return ApiResponse.of(
        AlertSuccessCode.ALERT_SUCCESS,
        ConditionResponse.from(
            alerts.addCondition(
                subscriptionId, token, request.itemId(), request.comparator(), request.price())));
  }

  @DeleteMapping(
      value = "/api/v1/alerts/subscriptions/{subscriptionId}/conditions/{conditionId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "알림 조건 지우기", description = ERRORS)
  public ApiResponse<Void> deleteCondition(
      @PathVariable UUID subscriptionId,
      @PathVariable UUID conditionId,
      @Parameter(description = "관리 토큰")
          @RequestHeader(value = TOKEN_HEADER, required = false)
          @Size(max = 128)
          String token) {
    alerts.deleteCondition(subscriptionId, token, conditionId);
    return ApiResponse.of(AlertSuccessCode.ALERT_SUCCESS, null);
  }

  public record SubscribeRequest(
      @Schema(description = "PushSubscription.endpoint(브라우저 푸시 서비스 https 주소)")
          @NotBlank
          @Size(max = 1024)
          String endpoint,
      @NotNull @Valid SubscribeKeys keys) {
    @Override
    public String toString() {
      return "SubscribeRequest[***]";
    }
  }

  public record SubscribeKeys(
      @Schema(description = "base64url") @NotBlank @Size(max = 128) String p256dh,
      @Schema(description = "base64url") @NotBlank @Size(max = 64) String auth) {
    @Override
    public String toString() {
      return "SubscribeKeys[***]";
    }
  }

  public record ConditionRequest(
      @Positive long itemId,
      @NotNull AlertComparator comparator,
      @NotNull
          @DecimalMin("0.01")
          @DecimalMax("999999999999.99")
          @Digits(integer = 12, fraction = 2)
          BigDecimal price) {}

  public record VapidKeyResponse(
      @Schema(description = "base64url 비압축 P-256 공개 키") String publicKey) {}

  public record SubscriptionResponse(
      UUID subscriptionId,
      @Schema(description = "관리 토큰. 이 응답에서만 보이고 다시 보여 주지 않습니다. 이후 요청의 X-Alert-Token 헤더")
          String manageToken) {
    @Override
    public String toString() {
      return "SubscriptionResponse[" + subscriptionId + "]";
    }
  }

  public record AlertItemResponse(
      long itemId,
      String itemName,
      @Schema(description = "현재 판매 최저가(매물이 없으면 null)") @NullableResponse BigDecimal currentMinPrice,
      Instant observedAt) {
    static AlertItemResponse from(AlertStore.ItemPrice item) {
      return new AlertItemResponse(
          item.itemId(), item.itemName(), item.currentMinPrice(), item.observedAt());
    }
  }

  public record ConditionResponse(
      UUID conditionId,
      long itemId,
      String itemName,
      @Schema(description = "AT_OR_BELOW(이하) 또는 AT_OR_ABOVE(이상)") String comparator,
      BigDecimal price,
      @Schema(description = "지금 조건에 맞는가(만들 때 맞으면 보내지 않고 시작)") boolean matchedNow,
      @Schema(description = "그 품목의 현재 판매 최저가(매물이 없으면 null)") @NullableResponse
          BigDecimal currentMinPrice,
      Instant observedAt,
      @Schema(description = "마지막으로 보낸 시각(없으면 null)") @NullableResponse Instant lastSentAt,
      Instant createdAt) {
    static ConditionResponse from(AlertService.ConditionView view) {
      var condition = view.condition();
      return new ConditionResponse(
          condition.conditionId(),
          condition.itemId(),
          condition.itemName(),
          condition.comparator().name(),
          condition.price(),
          view.matchedNow(),
          condition.currentMinPrice(),
          condition.observedAt(),
          condition.lastSentAt(),
          condition.createdAt());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @Conditional(AlertsEnabledCondition.class)
  static class AlertOpenApiConfiguration {
    @Bean
    OpenApiCustomizer alertResponseContract() {
      return document -> {
        var schemas = document.getComponents().getSchemas();
        for (var type :
            List.of(
                VapidKeyResponse.class,
                SubscriptionResponse.class,
                AlertItemResponse.class,
                ConditionResponse.class)) ResponseContracts.apply(type, schemas);
      };
    }
  }
}
