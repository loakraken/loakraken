package com.loakraken.market.domain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아크 그리드 젬 거래소 품목(M49 G5). 데이터 파일 market/ark-grid-gems.json(공식 230000 분류 전수 조회, 출처는 파일 안)이고 가격은 없다
 * — 시세는 보드 탭 전체 스캔에서 읽는다({@code ArkGridGemPriceService}).
 */
public record ArkGridGemCatalog(String source, List<Gem> gems) {
  private static final ArkGridGemCatalog INSTANCE = read();

  public ArkGridGemCatalog {
    Objects.requireNonNull(source);
    gems = List.copyOf(gems);
  }

  /** 공식 상품 ID, 공식 품목 이름, 젬 종류(안정·견고·불변·침식·왜곡·붕괴), 등급(고급·희귀·영웅). */
  public record Gem(long id, String name, String type, String grade) {
    public Gem {
      if (id < 1 || blank(name) || blank(type) || blank(grade))
        throw new IllegalArgumentException("잘못된 아크 그리드 젬 품목");
    }
  }

  public static ArkGridGemCatalog load() {
    return INSTANCE;
  }

  /** 종류·등급의 품목. 없으면 빈 값. */
  public Optional<Gem> find(String type, String grade) {
    return gems.stream()
        .filter(gem -> gem.type().equals(type) && gem.grade().equals(grade))
        .findFirst();
  }

  private static ArkGridGemCatalog read() {
    try (var input = ArkGridGemCatalog.class.getResourceAsStream("/market/ark-grid-gems.json")) {
      if (input == null) throw new IllegalStateException("아크 그리드 젬 품목 파일이 없습니다.");
      var node = JsonMapper.builder().build().readTree(input);
      var gems = new ArrayList<Gem>();
      for (var gem : node.path("gems"))
        gems.add(
            new Gem(
                gem.path("id").asLong(),
                gem.path("name").asString(),
                gem.path("type").asString(),
                gem.path("grade").asString()));
      if (gems.stream().map(Gem::id).distinct().count() != gems.size())
        throw new IllegalStateException("아크 그리드 젬 품목 ID가 겹칩니다.");
      return new ArkGridGemCatalog(node.path("source").asString(), gems);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
