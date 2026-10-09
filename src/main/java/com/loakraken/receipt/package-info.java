/** 원정대 영수증(receipt-v1, #173). 장비 재련 기대 비용과 거래 가능 보석 시세를 원정대 단위로 환산한다. */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
      "common::api",
      "character::application",
      "character::domain",
      "market::application",
      "market::domain",
      "refining::application",
      "refining::domain"
    })
package com.loakraken.receipt;
