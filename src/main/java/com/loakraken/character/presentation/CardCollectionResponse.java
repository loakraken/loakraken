package com.loakraken.character.presentation;

import com.loakraken.character.application.CharacterCardService;
import com.loakraken.character.domain.CardCollection;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(
    description =
        "원정대 카드 기록(card-record-v1). 원정대 캐릭터가 검색되거나 수집될 때 장착한 카드를 기록한 값이라 실제 보유의 하한입니다. "
            + "기록이 없어도 200이며 record는 null, cards·sets는 빈 배열입니다.")
public record CardCollectionResponse(
    String characterName,
    String serverName,
    @NullableResponse
        @Schema(
            description = "아직 판정 전이면 null",
            allowableValues = {"DEALER", "SUPPORT"})
        String position,
    @Schema(description = "같은 서버 원정대 캐릭터 수") int rosterCharacters,
    @Schema(description = "그중 카드 기록이 있는 캐릭터 수") int observedCharacters,
    String ruleVersion,
    CardCollectionElementRecordsResponse elementRecords,
    @Schema(description = "카드별 최고 각성(이름순)") List<CardCollectionCardResponse> cards,
    @Schema(description = "보유 카드로 구성할 수 있는 2세트 이상 세트(각성 합 내림차순). 장착한 적이 없어도 계산합니다.")
        List<CardCollectionSetResponse> sets) {
  static CardCollectionResponse from(CharacterCardService.Collection collection) {
    var result = collection.result();
    return new CardCollectionResponse(
        collection.characterName(),
        collection.serverName(),
        collection.position(),
        collection.rosterCharacters(),
        collection.observedCharacters(),
        CardCollection.RULE_VERSION,
        new CardCollectionElementRecordsResponse(slots(result.dealer()), slots(result.supporter())),
        result.cards().stream().map(CardCollectionCardResponse::from).toList(),
        result.sets().stream().map(CardCollectionSetResponse::from).toList());
  }

  private static List<CardCollectionElementSlotResponse> slots(
      List<CardCollection.ElementRecord> records) {
    return records.stream()
        .map(
            record ->
                new CardCollectionElementSlotResponse(
                    record.element().name(),
                    record.setName(),
                    record.record() == null
                        ? null
                        : CardCollectionSetRecordResponse.from(record.record())))
        .toList();
  }

  @Schema(description = "속성 6칸. 순서는 성·암·뇌·토·수·화(LIGHT, DARK, LIGHTNING, EARTH, WATER, FIRE)")
  public record CardCollectionElementRecordsResponse(
      List<CardCollectionElementSlotResponse> dealer,
      List<CardCollectionElementSlotResponse> supporter) {}

  public record CardCollectionElementSlotResponse(
      @Schema(allowableValues = {"LIGHT", "DARK", "LIGHTNING", "EARTH", "WATER", "FIRE"})
          String element,
      @Schema(description = "공식 세트명", example = "세상을 구하는 빛") String setName,
      @NullableResponse @Schema(description = "2세트 이상 구성할 수 있을 때만")
          CardCollectionSetRecordResponse record) {}

  public record CardCollectionSetRecordResponse(
      int cardCount,
      @Schema(description = "사용한 카드의 실제 각성 합") int awakening,
      @NullableResponse @Schema(description = "달성한 각성합계 단계(예: 30). 학습한 효과 기준")
          Integer activeAwakening,
      Instant observedAt,
      @Schema(description = "가장 최근 기록을 남긴 원정대 캐릭터") String recordedBy) {
    static CardCollectionSetRecordResponse from(CardCollection.FormedSet set) {
      return new CardCollectionSetRecordResponse(
          set.cardCount(),
          set.awakening(),
          set.activeAwakening(),
          set.observedAt(),
          set.recordedBy());
    }
  }

  public record CardCollectionCardResponse(
      String name,
      @NullableResponse String grade,
      @NullableResponse String icon,
      int awakeCount,
      @NullableResponse Integer awakeTotal,
      Instant observedAt,
      String recordedBy) {
    static CardCollectionCardResponse from(CardCollection.OwnedCard card) {
      return new CardCollectionCardResponse(
          card.name(),
          card.grade(),
          card.icon(),
          card.awakeCount(),
          card.awakeTotal(),
          card.observedAt(),
          card.recordedBy());
    }
  }

  public record CardCollectionSetResponse(
      String setName,
      int cardCount,
      int awakening,
      @NullableResponse Integer activeAwakening,
      @Schema(description = "사용한 카드(각성 높은 순, 최대 6장)") List<String> cards,
      @Schema(description = "학습한 세트 효과. 공식 응답에서 아직 보지 못한 단계는 빠집니다")
          List<CardCollectionSetEffectResponse> effects,
      Instant observedAt,
      String recordedBy) {
    static CardCollectionSetResponse from(CardCollection.FormedSet set) {
      return new CardCollectionSetResponse(
          set.setName(),
          set.cardCount(),
          set.awakening(),
          set.activeAwakening(),
          set.cards(),
          set.effects().stream()
              .map(
                  effect ->
                      new CardCollectionSetEffectResponse(
                          effect.pieces(),
                          effect.awakening(),
                          effect.description(),
                          effect.active()))
              .toList(),
          set.observedAt(),
          set.recordedBy());
    }
  }

  public record CardCollectionSetEffectResponse(
      int pieces,
      @Schema(description = "각성합계 조건, 없으면 0") int awakening,
      String description,
      boolean active) {}
}
