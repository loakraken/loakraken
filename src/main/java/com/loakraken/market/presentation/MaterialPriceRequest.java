package com.loakraken.market.presentation;

import com.loakraken.market.domain.MaterialPriceBasis;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MaterialPriceRequest(
    @NotNull @Schema(description = "단가 계산에 쓸 묶음 가격. 자동 대체하지 않습니다.") MaterialPriceBasis priceBasis,
    @NotNull
        @Size(min = 1, max = 20)
        @Schema(description = "재료 이름(재련 계획 응답의 이름 그대로). 운명의 파편은 주머니 시세로 환산합니다.")
        List<@NotBlank @Size(max = 64) String> names) {
  List<String> normalizedNames() {
    return names.stream().map(name -> name.strip()).toList();
  }
}
