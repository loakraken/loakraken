package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CardCollection.Element;
import com.loakraken.character.domain.CardCollection.OwnedCard;
import com.loakraken.character.domain.CardCollection.SetEffect;
import com.loakraken.character.domain.CardCollection.SetMember;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardCollectionTest {
  private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-08T00:00:00Z");
  private static final List<String> LEGION = List.of("발탄", "비아키스", "쿠크세이튼", "아브렐슈드", "일리아칸", "카멘");

  @Test
  void 장착_카드의_세트_효과에서_세트명_장수_각성합계와_구성원을_학습한다() {
    var cards =
        new CharacterLoadout.Cards(
            List.of(card(0, "샨디", 5), card(1, "카단", 5), card(2, "에스더 시엔", 3)),
            List.of(
                new CharacterLoadout.CardSet(
                    0,
                    List.of(0, 1),
                    List.of(
                        new CharacterLoadout.Effect("세상을 구하는 빛 2세트", "암속성 피해 감소 +10.00%"),
                        new CharacterLoadout.Effect("세상을 구하는 빛 6세트 (30각성합계)", "성속성 피해 +4.00%"),
                        new CharacterLoadout.Effect("해석할 수 없는 이름", "무시")))));
    var learned = CardCollection.learn(cards);
    assertThat(learned.members())
        .containsExactly(new SetMember("세상을 구하는 빛", "샨디"), new SetMember("세상을 구하는 빛", "카단"));
    assertThat(learned.effects())
        .containsExactly(
            new SetEffect("세상을 구하는 빛", 2, 0, "암속성 피해 감소 +10.00%"),
            new SetEffect("세상을 구하는 빛", 6, 30, "성속성 피해 +4.00%"));
    assertThat(CardCollection.learn(null).members()).isEmpty();
  }

  @Test
  void 장착한_적_없는_세트도_원정대_카드로_구성하고_각성이_높은_6장만_쓴다() {
    // 기준 캐릭터 예: 군단장 카드 6장(4·5·5·4·5·5)이면 장착 기록이 없어도 카제로스의 군단장 6세트 28각성이다.
    int[] awake = {4, 5, 5, 4, 5, 5};
    var owned = new ArrayList<OwnedCard>();
    for (int i = 0; i < LEGION.size(); i++)
      owned.add(owned(LEGION.get(i), awake[i], i < 3 ? T1 : T2, i < 3 ? "본캐" : "부캐"));
    // 같은 카드는 더 높은 각성 기록을 쓴다.
    owned.add(owned("발탄", 2, T2, "부캐"));
    var members = LEGION.stream().map(name -> new SetMember("카제로스의 군단장", name)).toList();
    var effects =
        List.of(
            new SetEffect("카제로스의 군단장", 2, 0, "성속성 피해 감소 +10.00%"),
            new SetEffect("카제로스의 군단장", 6, 12, "공격 속성을 암속성으로 변환"),
            new SetEffect("카제로스의 군단장", 6, 24, "암속성 피해 +4.00%"),
            new SetEffect("카제로스의 군단장", 6, 30, "암속성 피해 +4.00%"));

    var result = CardCollection.compute(owned, members, effects);

    var dark = result.dealer().get(1);
    assertThat(dark.element()).isEqualTo(Element.DARK);
    assertThat(dark.record().cardCount()).isEqualTo(6);
    assertThat(dark.record().awakening()).isEqualTo(28);
    assertThat(dark.record().activeAwakening()).isEqualTo(24);
    assertThat(dark.record().observedAt()).isEqualTo(T2);
    assertThat(dark.record().recordedBy()).isEqualTo("부캐");
    assertThat(dark.record().effects())
        .extracting(CardCollection.FormedEffect::active)
        .containsExactly(true, true, true, false);
    assertThat(result.dealer().get(0).record()).isNull();
    assertThat(result.supporter())
        .extracting(CardCollection.ElementRecord::record)
        .containsOnlyNulls();
    assertThat(result.cards()).hasSize(6);
    assertThat(result.cards().stream().filter(card -> card.name().equals("발탄")).findFirst())
        .get()
        .extracting(OwnedCard::awakeCount)
        .isEqualTo(4);
  }

  @Test
  void 일곱_장_세트는_각성_높은_여섯_장만_쓰고_한_장뿐인_세트는_기록하지_않는다() {
    var light = List.of("샨디", "아제나&이난나", "카단", "바훈투르", "실리안", "웨이", "니나브");
    var owned = new ArrayList<OwnedCard>();
    for (int i = 0; i < light.size(); i++) owned.add(owned(light.get(i), i == 6 ? 1 : 5, T1, "본캐"));
    owned.add(owned("에스더 루테란", 5, T1, "본캐"));
    var members = new ArrayList<SetMember>();
    light.forEach(name -> members.add(new SetMember("세상을 구하는 빛", name)));
    members.add(new SetMember("다른 세트", "에스더 루테란"));

    var result = CardCollection.compute(owned, members, List.of());

    var record = result.dealer().get(0).record();
    assertThat(record.cardCount()).isEqualTo(6);
    assertThat(record.awakening()).isEqualTo(30);
    assertThat(record.activeAwakening()).isNull();
    assertThat(record.cards()).doesNotContain("니나브");
    assertThat(result.sets())
        .extracting(CardCollection.FormedSet::setName)
        .containsExactly("세상을 구하는 빛");
  }

  @Test
  void 기록이_없으면_속성_칸은_비고_목록은_비어_있다() {
    var result = CardCollection.compute(List.of(), List.of(), List.of());
    assertThat(result.dealer()).hasSize(6);
    assertThat(result.dealer())
        .extracting(CardCollection.ElementRecord::element)
        .containsExactly(Element.values());
    assertThat(result.supporter().get(0).setName()).isEqualTo("남겨진 바람의 절벽");
    assertThat(result.cards()).isEmpty();
    assertThat(result.sets()).isEmpty();
  }

  private static CharacterLoadout.Card card(int slot, String name, int awake) {
    return new CharacterLoadout.Card(slot, name, "icon", awake, 5, "전설");
  }

  private static OwnedCard owned(String name, int awake, Instant at, String by) {
    return new OwnedCard(name, "전설", "icon", awake, 5, at, by);
  }
}
