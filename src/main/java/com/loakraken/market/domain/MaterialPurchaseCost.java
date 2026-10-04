package com.loakraken.market.domain;

import java.math.BigDecimal;

public record MaterialPurchaseCost(
    long bundleQuantity, long purchaseQuantity, long surplusQuantity, BigDecimal costGold) {
  public static MaterialPurchaseCost calculate(
      long missingQuantity, int bundleCount, BigDecimal pricePerBundle) {
    if (missingQuantity < 0
        || missingQuantity > 999_999_999L
        || bundleCount < 1
        || pricePerBundle == null
        || pricePerBundle.signum() <= 0) {
      throw new IllegalArgumentException("잘못된 재료 구매 조건");
    }
    long bundles = missingQuantity / bundleCount + (missingQuantity % bundleCount == 0 ? 0 : 1);
    long quantity = Math.multiplyExact(bundles, bundleCount);
    return new MaterialPurchaseCost(
        bundles,
        quantity,
        quantity - missingQuantity,
        pricePerBundle.multiply(BigDecimal.valueOf(bundles)));
  }

  public static MaterialPurchaseCost covered() {
    return new MaterialPurchaseCost(0, 0, 0, BigDecimal.ZERO);
  }
}
