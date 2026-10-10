# 로컬 수집기 상시 실행 (임시)

개발용 수집기·예열 지정 인스턴스를 로컬 docker compose로 띄운다(PM 총괄 결정 2026-10-09, 이슈 #148). 세션 백그라운드 실행은 2시간 한도로 멈추기 때문이다. **사용자 PC와 Docker Desktop이 켜져 있는 동안만** 돈다. 운영 서버가 생기면 운영 스택의 수집기로 옮기고 이 프로필은 끈다.

## 규칙

- `STATS_COLLECTOR_ENABLED=true`·`MARKET_WARMUP_ENABLED=true`는 **이 컨테이너 한 곳만** 켠다. 다른 로컬 인스턴스(`gradlew bootRun`)는 설정하지 않으면 꺼진다.
- 같은 개발 키를 쓰는 모든 로컬 인스턴스는 같은 Valkey(`loakraken-valkey`)의 공유 호출 게이트로 90회/분을 나눈다(#107). 게이트 키 ID는 키에서 만들어지므로 따로 맞출 것이 없다.
- 8080은 이 컨테이너가 쓴다. 8080에 `bootRun`을 따로 띄우지 않는다(포트 충돌).
- 개발 키는 셸의 `LOSTARK_API_KEY`(Windows 사용자 환경 변수)를 compose 비밀값으로 넘긴다. 컨테이너에는 `/run/secrets/LOSTARK_API_KEY` 파일로만 들어가고 `docker inspect` 환경 변수에는 보이지 않는다. 키를 명령줄·파일·로그에 쓰지 않는다.
- **새 마이그레이션을 만드는 기능 브랜치는 별도 DB로 실행한다**(예: `POSTGRES_DB=loakraken_<이슈>`). 로컬 DB를 develop 수집기와 함께 쓰므로, 병합 전 마이그레이션을 적용한 뒤 그 파일을 고치면 수집기가 재시작할 때 Flyway 체크섬 불일치로 멈춘다(#100 V7 사례). 별도 DB는 `docker exec loakraken-postgres createdb -U <POSTGRES_USER> loakraken_<이슈>`로 만들고 쓴 뒤 지운다.
- 로컬 DB에도 수집한 캐릭터 데이터가 쌓인다. **로컬 DB·덤프를 외부(클라우드 드라이브·메신저·저장소 등)로 복사하지 않는다**(개인정보 처리방침).

## 띄우기

develop 기준 전용 작업 트리에서 띄운다(기능 브랜치 코드가 수집기로 돌지 않게). DB·Valkey 설정은 원래 저장소의 `.env`를 쓴다.

```bash
git -C C:/LOAKraken/loakraken fetch origin
git -C C:/LOAKraken/loakraken worktree add --detach C:/LOAKraken/wt/collector origin/develop
cd C:/LOAKraken/wt/collector
docker compose --env-file C:/LOAKraken/loakraken/.env --profile collector up -d --build --wait collector
```

- `compose.yaml`에 `name: loakraken`이 있어 작업 트리에서 실행해도 이미 떠 있는 `loakraken-postgres`·`loakraken-valkey`를 그대로 쓴다.
- 확인: `curl -s http://127.0.0.1:8080/actuator/health` → `"status":"UP"`. 수집 로그는 `docker logs -f loakraken-collector`.
- 재시작 정책은 `unless-stopped`다. PC를 다시 켜면 Docker Desktop이 켜질 때 함께 뜬다. Docker Desktop 설정 → General → "Start Docker Desktop when you sign in to your computer"를 켜 둔다(사용자).

## develop 최신으로 갱신

갱신 전에 `docker logs --tail 50 loakraken-collector`로 수집 회차가 정상인지 보고, 갱신 뒤 `curl -s http://127.0.0.1:8080/actuator/health`(UP)와 로그의 수집 회차·429 여부를 다시 확인한다.

```bash
cd C:/LOAKraken/wt/collector
git fetch origin && git checkout --detach origin/develop
docker compose --env-file C:/LOAKraken/loakraken/.env --profile collector up -d --build --wait collector
```

DB 마이그레이션이 들어간 develop은 수집기를 갱신할 때 적용된다(로컬 DB를 함께 쓰는 다른 인스턴스도 같은 스키마를 보게 된다).

## 멈추기

```bash
docker stop loakraken-collector          # 잠시 멈춤(다시: docker start loakraken-collector)
docker rm -f loakraken-collector         # 없애기(DB·Valkey는 그대로)
```

`docker compose down`은 postgres·valkey까지 내리므로 쓰지 않는다.

## 기본 `docker compose up`과의 관계

프로필을 주지 않으면 지금처럼 postgres·valkey만 뜬다. `LOSTARK_API_KEY`가 없어도 동작한다.
