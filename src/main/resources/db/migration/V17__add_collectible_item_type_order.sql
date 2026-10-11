-- 수집품 보유율 후속(#306 P2): 공식 응답의 종류 순서. 다음 관측 때 채워지고, 그 전에는 0이다.
ALTER TABLE collectible_item ADD COLUMN type_order SMALLINT NOT NULL DEFAULT 0;
