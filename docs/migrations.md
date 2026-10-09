# 마이그레이션 원칙

Flyway 마이그레이션(`src/main/resources/db/migration`)을 만들 때 지킨다.

- 번호는 develop 병합 순서대로 붙인다. 열린 PR끼리 번호가 겹치거나 순서가 바뀌면 병합 순서를 PR 설명에 적는다(V9 #139 → V10 #126 → V11 #165 → V12 #167).
- 이미 병합된 마이그레이션 파일은 고치지 않는다. 바꿀 것은 새 번호로 만든다(체크섬이 달라지면 모든 환경이 시작하지 않는다).
- 새 테이블이 `characters`를 참조하면 `ON DELETE CASCADE`로 둔다. B9 보유 기간 정리(#167)가 캐릭터 행만 지운다.
- **큰 테이블의 FK·CHECK 추가는 두 단계로 한다**(#167 P2): 먼저 `ADD CONSTRAINT … NOT VALID`(새 행만 검사, 짧은 잠금)로 넣고, 다른 마이그레이션에서 `VALIDATE CONSTRAINT`(기존 행 검사, 쓰기 막지 않음)를 한다. V12는 테이블이 작을 때라 한 번에 했다.
- 인덱스는 운영 테이블이 커진 뒤에는 `CREATE INDEX CONCURRENTLY`를 쓴다. 이때는 그 파일만 트랜잭션 밖에서 돌도록 Flyway 설정(`-- flyway:executeInTransaction=false`)을 붙인다.
- 열 삭제·이름 변경은 코드가 먼저 안 쓰게 배포한 뒤 다음 마이그레이션에서 한다(무중단).
