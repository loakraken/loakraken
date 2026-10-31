package com.loakraken.market.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(
    description =
        "각인 그림 목록. 공식 각인 응답에는 아이콘이 없어, 유물 각인서(거래소) 공식 아이콘을 각인 그림으로 씁니다. 저장해 둔 시세에서만 만들며 공식 호출이 없습니다. 목록에 없는 각인은 그림 없이 표시합니다.")
public record EngravingIconsResponse(
    @Schema(description = "각인 이름순") List<EngravingIconResponse> icons) {
  static EngravingIconsResponse from(Map<String, String> icons) {
    return new EngravingIconsResponse(
        icons.entrySet().stream()
            .map(entry -> new EngravingIconResponse(entry.getKey(), entry.getValue()))
            .toList());
  }

  public record EngravingIconResponse(
      @Schema(description = "공백을 뺀 각인 이름. 화면의 각인 이름도 공백을 빼고 맞춥니다", example = "예리한둔기") String key,
      @Schema(description = "공식 이미지 서버의 유물 각인서 아이콘 주소") String icon) {}
}
