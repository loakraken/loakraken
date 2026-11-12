-- 모든 표와 열에 한국어 설명(COMMENT)을 단다. DB 도구에서 표를 볼 때 뜻을 바로 알게 하려는 것이다.
-- 설명만 바꾼다: 스키마·데이터 변경 없음. COMMENT ON은 덮어쓰기라 다시 실행해도 같은 결과다(멱등).
-- 시각 열(TIMESTAMPTZ)은 절대 시각이다(화면은 KST로 보여 준다). 날짜 열은 특별히 적지 않으면 KST 날짜다. 가격 단위는 골드(G)다.

-- characters
COMMENT ON TABLE characters IS '캐릭터. 검색하거나 수집기가 원정대에서 발견한 캐릭터마다 한 행(서버+이름이 유일).';
COMMENT ON COLUMN characters.character_id IS '캐릭터 ID(내부 UUID).';
COMMENT ON COLUMN characters.character_name IS '캐릭터 이름(공식 표기 그대로).';
COMMENT ON COLUMN characters.server_name IS '서버 이름.';
COMMENT ON COLUMN characters.class_name IS '직업 이름.';
COMMENT ON COLUMN characters.item_level IS '아이템 레벨(마지막 관측).';
COMMENT ON COLUMN characters.combat_power IS '전투력(마지막 관측). 원정대로만 발견해 프로필을 아직 못 받았으면 NULL.';
COMMENT ON COLUMN characters.last_synced_at IS '캐릭터 행을 마지막으로 갱신한 시각.';
COMMENT ON COLUMN characters.position IS '포지션: DEALER(딜러) 또는 SUPPORT(서포터). 프로필을 받기 전에는 NULL.';
COMMENT ON COLUMN characters.profile_refreshed_at IS '프로필(전투력 등)을 마지막으로 성공해서 받은 시각. 통계 모집단의 최근 확인 기준.';
COMMENT ON COLUMN characters.roster_scanned_at IS '수집기가 이 캐릭터의 원정대 목록을 마지막으로 훑은 시각.';
COMMENT ON COLUMN characters.discovered_at IS '수집기가 원정대에서 이 캐릭터를 처음 발견한 시각. 사용자 검색으로 먼저 생긴 행은 NULL.';
COMMENT ON COLUMN characters.roster_key IS '원정대 키. 같은 계정의 캐릭터들이 같은 값을 가진다(대표 캐릭터·원정대 집계용). 아직 모르면 NULL.';
COMMENT ON COLUMN characters.gem_mean_level IS '4티어 보석 평균 레벨(소수 둘째 자리).';
COMMENT ON COLUMN characters.profile_checked_at IS '수집기가 프로필을 마지막으로 시도한 시각(성공·못 찾음·해석 실패 모두). 다시 고를 순서에만 쓴다.';
COMMENT ON COLUMN characters.expedition_level IS '원정대 레벨. 프로필에 값이 없으면 이전 값을 둔다.';
COMMENT ON COLUMN characters.guild_name IS '길드 이름. 길드가 없으면 NULL.';
COMMENT ON COLUMN characters.image_url IS '공식 프로필 이미지 주소(이름 자동완성 초상). 없으면 NULL.';
COMMENT ON COLUMN characters.bracelet_efficiency_pct IS '팔찌 효율(%, 전투력 기여 모델). 계산된 값만 남긴다.';
COMMENT ON COLUMN characters.bracelet_rule_version IS '팔찌 효율을 계산한 규칙 버전. 랭킹은 지금 버전 값만 센다.';
COMMENT ON COLUMN characters.ark_grid_gem_pct IS '아크 그리드 젬 효율(%, 전투력 기여 모델). 계산된 값만 남긴다.';
COMMENT ON COLUMN characters.ark_grid_rule_version IS '젬 효율을 계산한 규칙 버전. 랭킹은 지금 버전 값만 센다.';
COMMENT ON COLUMN characters.efficiency_observed_at IS '효율 값들을 마지막으로 계산한 관측 시각.';
COMMENT ON COLUMN characters.best_combat_power IS '우리가 관측한 기록 중 가장 높은 전투력.';
COMMENT ON COLUMN characters.guild_member_grade IS '길드 직위(공식 문자열 그대로). 길드가 없으면 NULL.';
COMMENT ON COLUMN characters.bracelet_real_pct IS '팔찌 실 효율(%). 캐릭터 화면 값과 같게 자릿수를 제한하지 않는다.';
COMMENT ON COLUMN characters.bracelet_real_rule_version IS '팔찌 실 효율을 계산한 규칙 버전.';
COMMENT ON COLUMN characters.ark_grid_real_pct IS '아크 그리드 젬 실 효율(%).';
COMMENT ON COLUMN characters.ark_grid_real_rule_version IS '젬 실 효율을 계산한 규칙 버전.';
COMMENT ON COLUMN characters.position_observed_at IS '포지션을 마지막으로 정한 관측 시각. 이보다 늦게 도착한 옛 관측은 포지션을 바꾸지 않는다.';
COMMENT ON COLUMN characters.emblems_observed_at IS '장착 휘장을 마지막으로 관측한 시각. NULL이면 모름(휘장 없음과 구분).';

-- character_snapshot
COMMENT ON TABLE character_snapshot IS '캐릭터 공식 응답(armory) 원본 스냅숏. 관측마다 한 행.';
COMMENT ON COLUMN character_snapshot.snapshot_id IS '스냅숏 ID(UUID).';
COMMENT ON COLUMN character_snapshot.snapshot_sequence IS '저장 순서 번호(같은 시각 정렬용).';
COMMENT ON COLUMN character_snapshot.character_id IS '캐릭터 ID. 캐릭터를 지우면 함께 지운다.';
COMMENT ON COLUMN character_snapshot.schema_version IS '저장 형식 버전.';
COMMENT ON COLUMN character_snapshot.armory_json IS '캐릭터 공식 응답 원본(JSON 객체).';
COMMENT ON COLUMN character_snapshot.siblings_json IS '원정대 캐릭터 목록 원본(JSON 배열).';
COMMENT ON COLUMN character_snapshot.fetched_at IS '공식 응답을 받은 시각.';
COMMENT ON COLUMN character_snapshot.created_at IS '행을 저장한 시각.';

-- character_observation
COMMENT ON TABLE character_observation IS '캐릭터 관측 이력. 같은 값이 이어지면 새 행 없이 마지막 확인 시각만 늘린다(구간 행).';
COMMENT ON COLUMN character_observation.observation_id IS '관측 ID(UUID).';
COMMENT ON COLUMN character_observation.observation_sequence IS '저장 순서 번호(같은 시각 정렬용).';
COMMENT ON COLUMN character_observation.character_id IS '캐릭터 ID. 캐릭터를 지우면 함께 지운다.';
COMMENT ON COLUMN character_observation.observed_at IS '이 값을 처음 관측한 시각(구간 시작).';
COMMENT ON COLUMN character_observation.last_confirmed_at IS '이 값을 마지막으로 다시 확인한 시각(구간 끝).';
COMMENT ON COLUMN character_observation.class_name IS '직업 이름.';
COMMENT ON COLUMN character_observation.character_level IS '전투 레벨.';
COMMENT ON COLUMN character_observation.item_level IS '아이템 레벨.';
COMMENT ON COLUMN character_observation.combat_power IS '전투력.';
COMMENT ON COLUMN character_observation.expedition_level IS '원정대 레벨.';

-- character_title_observation
COMMENT ON TABLE character_title_observation IS '캐릭터가 장착한 칭호의 관측 기록. 캐릭터·칭호마다 한 행.';
COMMENT ON COLUMN character_title_observation.character_id IS '캐릭터 ID. 캐릭터를 지우면 함께 지운다.';
COMMENT ON COLUMN character_title_observation.title IS '칭호 이름.';
COMMENT ON COLUMN character_title_observation.first_observed_at IS '이 칭호 장착을 처음 관측한 시각.';
COMMENT ON COLUMN character_title_observation.last_observed_at IS '이 칭호 장착을 마지막으로 관측한 시각.';

-- character_view_daily
COMMENT ON TABLE character_view_daily IS '캐릭터 조회수 일별 집계(반복 조회 포함). 조회한 사람의 정보는 저장하지 않는다.';
COMMENT ON COLUMN character_view_daily.character_name IS '조회된 캐릭터 이름.';
COMMENT ON COLUMN character_view_daily.view_date IS '조회 날짜(KST).';
COMMENT ON COLUMN character_view_daily.view_count IS '그날 조회 수.';

-- character_card
COMMENT ON TABLE character_card IS '캐릭터의 카드 기록. 장착 카드를 관측할 때마다 카드별 최고 각성을 남긴다.';
COMMENT ON COLUMN character_card.character_id IS '캐릭터 ID. 캐릭터를 지우면 함께 지운다.';
COMMENT ON COLUMN character_card.card_name IS '카드 이름.';
COMMENT ON COLUMN character_card.grade IS '카드 등급(공식 문자열).';
COMMENT ON COLUMN character_card.icon IS '카드 아이콘 주소(공식 이미지).';
COMMENT ON COLUMN character_card.awake_count IS '관측한 최고 각성 수.';
COMMENT ON COLUMN character_card.awake_total IS '최대 각성 수.';
COMMENT ON COLUMN character_card.observed_at IS '마지막으로 관측한 시각.';

-- card_set_member
COMMENT ON TABLE card_set_member IS '카드 세트 구성원. 공식 응답에서 세트별 카드 이름을 배운다.';
COMMENT ON COLUMN card_set_member.set_name IS '카드 세트 이름.';
COMMENT ON COLUMN card_set_member.card_name IS '세트에 속한 카드 이름.';
COMMENT ON COLUMN card_set_member.first_observed_at IS '이 구성을 처음 관측한 시각.';

-- card_set_effect
COMMENT ON TABLE card_set_effect IS '카드 세트 효과 문구. 세트·장수·각성 합계마다 한 행.';
COMMENT ON COLUMN card_set_effect.set_name IS '카드 세트 이름.';
COMMENT ON COLUMN card_set_effect.pieces IS '효과가 켜지는 카드 장수.';
COMMENT ON COLUMN card_set_effect.awakening IS '효과가 켜지는 각성 합계 조건. 조건이 없으면 0.';
COMMENT ON COLUMN card_set_effect.description IS '효과 설명 문구(공식 그대로).';
COMMENT ON COLUMN card_set_effect.observed_at IS '마지막으로 관측한 시각.';

-- character_popular_hourly
COMMENT ON TABLE character_popular_hourly IS '인기 검색 캐릭터 시간별 집계. 같은 사람·같은 캐릭터는 KST 하루 1회만 센다. 원본 IP는 저장하지 않는다.';
COMMENT ON COLUMN character_popular_hourly.character_name IS '캐릭터 이름.';
COMMENT ON COLUMN character_popular_hourly.server_name IS '서버 이름.';
COMMENT ON COLUMN character_popular_hourly.hour_bucket IS '집계 시간 칸(정시).';
COMMENT ON COLUMN character_popular_hourly.unique_views IS '그 시간 칸의 중복 없는 조회 수.';

-- public_exclusion
COMMENT ON TABLE public_exclusion IS '공개 순위(인기 검색·랭킹)에서 뺄 캐릭터(처리정지 목록). 운영자가 직접 등록한다.';
COMMENT ON COLUMN public_exclusion.exclusion_id IS '등록 ID.';
COMMENT ON COLUMN public_exclusion.character_name IS '뺄 캐릭터 이름.';
COMMENT ON COLUMN public_exclusion.server_name IS '서버 이름. NULL이면 모든 서버.';
COMMENT ON COLUMN public_exclusion.created_at IS '등록한 시각.';
COMMENT ON COLUMN public_exclusion.reason_code IS '등록 사유 코드.';

-- auction_price_daily
COMMENT ON TABLE auction_price_daily IS '경매장 시세 표의 칸별 일별 최저가. 그날(KST) 마지막 관측 값.';
COMMENT ON COLUMN auction_price_daily.cell_key IS '칸 키(검색 조건: 카테고리·등급·이름·품질·연마 효과의 해시).';
COMMENT ON COLUMN auction_price_daily.kst_date IS '날짜(KST).';
COMMENT ON COLUMN auction_price_daily.min_buy_price IS '즉시 구매 최저가(G).';
COMMENT ON COLUMN auction_price_daily.total_count IS '조건에 맞는 매물 수.';
COMMENT ON COLUMN auction_price_daily.observed_at IS '관측 시각.';

-- patch_notice
COMMENT ON TABLE patch_notice IS '공식 패치 공지 목록(제목 규칙에 맞는 공지만). 본문은 저장하지 않는다.';
COMMENT ON COLUMN patch_notice.notice_id IS '공식 공지 번호.';
COMMENT ON COLUMN patch_notice.title IS '공지 제목.';
COMMENT ON COLUMN patch_notice.category IS '분류: UPDATE(업데이트 내역)·BALANCE(밸런스)·CLIENT_PATCH(클라이언트 패치)·MAINTENANCE_COMPLETE(점검 완료).';
COMMENT ON COLUMN patch_notice.published_at IS '공지 게시 시각(공식 값, KST, 시간대 없음).';
COMMENT ON COLUMN patch_notice.url IS '공식 공지 주소.';
COMMENT ON COLUMN patch_notice.first_observed_at IS '처음 관측한 시각.';
COMMENT ON COLUMN patch_notice.last_observed_at IS '마지막으로 관측한 시각.';

-- character_equipment_summary
COMMENT ON TABLE character_equipment_summary IS '캐릭터 장비 요약 이력. 원문 없이 정규화한 특성만 남기고, 같으면 마지막 확인 시각만 늘린다(구간 행).';
COMMENT ON COLUMN character_equipment_summary.summary_id IS '요약 ID.';
COMMENT ON COLUMN character_equipment_summary.character_id IS '캐릭터 ID. 캐릭터를 지우면 함께 지운다.';
COMMENT ON COLUMN character_equipment_summary.observed_at IS '이 요약을 처음 관측한 시각(구간 시작).';
COMMENT ON COLUMN character_equipment_summary.last_confirmed_at IS '마지막으로 다시 확인한 시각(구간 끝).';
COMMENT ON COLUMN character_equipment_summary.schema_version IS '요약 형식 버전.';
COMMENT ON COLUMN character_equipment_summary.combat_power IS '전투력.';
COMMENT ON COLUMN character_equipment_summary.item_level IS '아이템 레벨.';
COMMENT ON COLUMN character_equipment_summary.character_level IS '전투 레벨.';
COMMENT ON COLUMN character_equipment_summary.class_name IS '직업 이름.';
COMMENT ON COLUMN character_equipment_summary.ark_passive_title IS '아크 패시브 이름.';
COMMENT ON COLUMN character_equipment_summary.features IS '정규화한 장비 특성(JSON).';
COMMENT ON COLUMN character_equipment_summary.features_hash IS '특성의 SHA-256 해시(16진). 같은 요약인지 비교용.';

-- roster_collectible
COMMENT ON TABLE roster_collectible IS '원정대 수집품(내실) 점수. 원정대·수집품 종류마다 한 행, 공식 값 그대로.';
COMMENT ON COLUMN roster_collectible.roster_key IS '원정대 키.';
COMMENT ON COLUMN roster_collectible.type IS '수집품 종류 이름(공식 문자열).';
COMMENT ON COLUMN roster_collectible.sort_order IS '공식 응답의 종류 순서(화면 열 순서).';
COMMENT ON COLUMN roster_collectible.point IS '모은 점수.';
COMMENT ON COLUMN roster_collectible.max_point IS '만점.';
COMMENT ON COLUMN roster_collectible.observed_at IS '관측 시각.';
COMMENT ON COLUMN roster_collectible.source_character_id IS '이 값을 읽은 캐릭터 ID. 그 캐릭터가 지워져도 값은 남는다.';

-- collectible_item
COMMENT ON TABLE collectible_item IS '수집품 항목 사전. 공식 수집품의 항목마다 한 행.';
COMMENT ON COLUMN collectible_item.item_id IS '항목 ID(공식 순서가 바뀌어도 그대로).';
COMMENT ON COLUMN collectible_item.type IS '수집품 종류 이름.';
COMMENT ON COLUMN collectible_item.name IS '항목 이름.';
COMMENT ON COLUMN collectible_item.sort_order IS '종류 안에서의 공식 순서.';
COMMENT ON COLUMN collectible_item.max_point IS '항목 만점(모코코 씨앗은 지역별 개수).';
COMMENT ON COLUMN collectible_item.last_seen_at IS '마지막으로 관측한 시각.';
COMMENT ON COLUMN collectible_item.type_order IS '공식 응답의 종류 순서. 관측 전에는 0.';

-- roster_collectible_item
COMMENT ON TABLE roster_collectible_item IS '원정대가 가진 수집품 항목. 원정대마다 한 행, 가진 항목만 (항목 ID, 점수) 평행 배열로 둔다.';
COMMENT ON COLUMN roster_collectible_item.roster_key IS '원정대 키.';
COMMENT ON COLUMN roster_collectible_item.item_ids IS '가진 항목 ID 배열(collectible_item.item_id).';
COMMENT ON COLUMN roster_collectible_item.points IS 'item_ids와 같은 순서의 점수 배열.';
COMMENT ON COLUMN roster_collectible_item.observed_at IS '관측 시각.';

-- push_subscription
COMMENT ON TABLE push_subscription IS '시세 알림 웹 푸시 구독. 로그인 없는 브라우저별 익명 구독이다(계정·IP 등 저장 안 함).';
COMMENT ON COLUMN push_subscription.subscription_id IS '구독 ID(UUID).';
COMMENT ON COLUMN push_subscription.endpoint IS '푸시 서비스가 준 전송 주소.';
COMMENT ON COLUMN push_subscription.p256dh IS '푸시 암호화 공개 키(브라우저가 준 값).';
COMMENT ON COLUMN push_subscription.auth IS '푸시 인증 비밀(브라우저가 준 값).';
COMMENT ON COLUMN push_subscription.manage_token_hash IS '구독 관리 토큰의 SHA-256 해시(16진). 토큰 원문은 저장하지 않는다.';
COMMENT ON COLUMN push_subscription.created_at IS '구독한 시각.';
COMMENT ON COLUMN push_subscription.last_used_at IS '마지막으로 쓴 시각(오래 쓰지 않은 구독 정리 기준).';

-- price_alert
COMMENT ON TABLE price_alert IS '시세 알림 조건. 구독마다 여러 개, 구독을 지우면 함께 지운다.';
COMMENT ON COLUMN price_alert.alert_id IS '알림 ID(UUID).';
COMMENT ON COLUMN price_alert.subscription_id IS '구독 ID.';
COMMENT ON COLUMN price_alert.item_id IS '거래소 품목 ID(공식).';
COMMENT ON COLUMN price_alert.comparator IS '조건: AT_OR_BELOW(최저가가 기준 이하) 또는 AT_OR_ABOVE(기준 이상).';
COMMENT ON COLUMN price_alert.price IS '기준 가격(G).';
COMMENT ON COLUMN price_alert.armed IS '조건이 지금 거짓이라 다음에 참이 되면 보낼 수 있는 상태.';
COMMENT ON COLUMN price_alert.pending_price IS '보낼 차례인 가격(G, 발송 대기). 없으면 NULL.';
COMMENT ON COLUMN price_alert.last_sent_at IS '마지막으로 알림을 보낸 시각.';
COMMENT ON COLUMN price_alert.created_at IS '알림을 만든 시각.';

-- alert_item_price
COMMENT ON TABLE alert_item_price IS '알림을 걸 수 있는 품목과 마지막으로 관측한 판매 최저가.';
COMMENT ON COLUMN alert_item_price.item_id IS '거래소 품목 ID(공식).';
COMMENT ON COLUMN alert_item_price.item_name IS '품목 이름.';
COMMENT ON COLUMN alert_item_price.current_min_price IS '판매 최저가(G). 매물이 없으면 NULL.';
COMMENT ON COLUMN alert_item_price.observed_at IS '관측 시각.';

-- market_price_minute
COMMENT ON TABLE market_price_minute IS '거래소 분 단위 시세(분 차트). 이미 받은 가격을 UTC 분 칸에 둔다. 2시간만 보관.';
COMMENT ON COLUMN market_price_minute.item_id IS '거래소 품목 ID(공식).';
COMMENT ON COLUMN market_price_minute.minute IS '분 칸(UTC 분의 시작).';
COMMENT ON COLUMN market_price_minute.item_name IS '품목 이름.';
COMMENT ON COLUMN market_price_minute.current_min_price IS '판매 최저가(G).';
COMMENT ON COLUMN market_price_minute.recent_price IS '최근 거래가(G).';
COMMENT ON COLUMN market_price_minute.sample_every_seconds IS '이 품목을 다시 받는 주기(초). 분보다 길면 분마다 값이 없다.';

-- character_emblem
COMMENT ON TABLE character_emblem IS '캐릭터가 장착한 휘장(마지막 관측). 아이콘 주소만 있고 이름은 없다.';
COMMENT ON COLUMN character_emblem.character_id IS '캐릭터 ID. 캐릭터를 지우면 함께 지운다.';
COMMENT ON COLUMN character_emblem.icon_url IS '휘장 아이콘 주소(공식 이미지, 쿼리·조각 뺀 형태).';

-- stats_population_snapshot
COMMENT ON TABLE stats_population_snapshot IS '전체 인구 통계 주간 스냅숏. 아이템 레벨이 있는 저장 캐릭터 수를 묶음별로 센다(처리정지 목록 제외).';
COMMENT ON COLUMN stats_population_snapshot.snapshot_date IS '스냅숏 날짜(KST).';
COMMENT ON COLUMN stats_population_snapshot.server_name IS '서버 이름. 빈 문자열이면 전체.';
COMMENT ON COLUMN stats_population_snapshot.dimension IS '묶음 기준: SERVER(서버별, 전체만)·CLASS(직업별)·LEVEL(아이템 레벨 10 단위).';
COMMENT ON COLUMN stats_population_snapshot.bucket IS '묶음 값(서버 이름·직업 이름·레벨 구간 하한).';
COMMENT ON COLUMN stats_population_snapshot.character_count IS '캐릭터 수.';

-- stats_population_snapshot_meta
COMMENT ON TABLE stats_population_snapshot_meta IS '인구 스냅숏을 만들 때의 정보. 날짜마다 한 행.';
COMMENT ON COLUMN stats_population_snapshot_meta.snapshot_date IS '스냅숏 날짜(KST).';
COMMENT ON COLUMN stats_population_snapshot_meta.exclusion_revision IS '만들 때의 처리정지 목록 값. 지금 목록과 다르면 읽을 때 다시 만든다.';
COMMENT ON COLUMN stats_population_snapshot_meta.built_at IS '만든 시각.';

-- character_polish_line
COMMENT ON TABLE character_polish_line IS '캐릭터 장신구 연마 효과 줄(마지막 관측). 관측마다 그 캐릭터의 줄을 바꾼다.';
COMMENT ON COLUMN character_polish_line.character_id IS '캐릭터 ID. 캐릭터를 지우면 함께 지운다.';
COMMENT ON COLUMN character_polish_line.line_no IS '줄 번호(캐릭터 안 순서).';
COMMENT ON COLUMN character_polish_line.slot IS '부위: NECKLACE(목걸이)·EARRING(귀걸이)·RING(반지).';
COMMENT ON COLUMN character_polish_line.item_level IS '장신구 아이템 레벨.';
COMMENT ON COLUMN character_polish_line.item_grade IS '장신구 등급(공식 문자열).';
COMMENT ON COLUMN character_polish_line.option_name IS '연마 효과 이름.';
COMMENT ON COLUMN character_polish_line.option_value IS '연마 효과 값(공식 표기 그대로).';
COMMENT ON COLUMN character_polish_line.colour IS '효과 등급: HIGH(상)·MID(중)·LOW(하). 모르면 NULL.';
COMMENT ON COLUMN character_polish_line.observed_at IS '관측 시각.';

-- title_equip_daily_meta
COMMENT ON TABLE title_equip_daily_meta IS '칭호 장착 일별 스냅숏을 만들 때의 정보. 날짜·서버마다 한 행.';
COMMENT ON COLUMN title_equip_daily_meta.snapshot_date IS '스냅숏 날짜(KST).';
COMMENT ON COLUMN title_equip_daily_meta.server_key IS '서버 이름. 빈 문자열이면 전체.';
COMMENT ON COLUMN title_equip_daily_meta.population IS '그날 집계의 모집단 원정대 수(비율의 분모).';
COMMENT ON COLUMN title_equip_daily_meta.built_at IS '만든 시각.';
COMMENT ON COLUMN title_equip_daily_meta.exclusion_revision IS '만들 때의 처리정지 목록 값. 지금 목록과 다르면 다음 만들기가 다시 만든다.';

-- title_equip_daily
COMMENT ON TABLE title_equip_daily IS '칭호 장착 일별 스냅숏(최근 30일 기준). 날짜·서버·칭호마다 한 행, 365일보다 오래되면 지운다.';
COMMENT ON COLUMN title_equip_daily.snapshot_date IS '스냅숏 날짜(KST).';
COMMENT ON COLUMN title_equip_daily.server_key IS '서버 이름. 빈 문자열이면 전체.';
COMMENT ON COLUMN title_equip_daily.title IS '칭호 이름.';
COMMENT ON COLUMN title_equip_daily.equipped_rosters IS '최근 30일 안에 이 칭호 장착이 확인된 원정대 수.';
COMMENT ON COLUMN title_equip_daily.observed_rosters IS '이 칭호 장착이 한 번이라도 기록된 원정대 수(기간 무관, 보유의 하한).';
COMMENT ON COLUMN title_equip_daily.new_rosters_30d IS '이 칭호를 처음 기록한 때가 최근 30일 안인 원정대 수.';
COMMENT ON COLUMN title_equip_daily.observed_characters IS '이 칭호 장착이 기록된 서로 다른 공개 캐릭터 수.';

-- market_price_daily
COMMENT ON TABLE market_price_daily IS '거래소 품목 일별 시세. 이미 받은 공식 상세의 최근 14일에서 끝난 날(KST 오늘 이전)만 덮어쓴다. 지우지 않는다.';
COMMENT ON COLUMN market_price_daily.item_id IS '거래소 품목 ID(공식).';
COMMENT ON COLUMN market_price_daily.trade_remain_key IS '거래 가능 횟수(변형 구분). 공식 값이 없으면 -1.';
COMMENT ON COLUMN market_price_daily.bundle_key IS '묶음 수(변형 구분). 공식 값이 없으면 -1.';
COMMENT ON COLUMN market_price_daily.kst_date IS '날짜(KST).';
COMMENT ON COLUMN market_price_daily.item_name IS '품목 이름.';
COMMENT ON COLUMN market_price_daily.avg_price IS '그날 평균 거래가(G).';
COMMENT ON COLUMN market_price_daily.trade_count IS '그날 거래 수.';
COMMENT ON COLUMN market_price_daily.observed_at IS '관측 시각.';
