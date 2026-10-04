# LOAKraken Backend

로스트아크 캐릭터 조회와 성장 분석을 위한 Java 25 / Spring Boot 4.1 백엔드입니다.

## 개발 환경

- JDK 25, Docker Engine 또는 Docker Desktop
- PostgreSQL 18.6, Valkey 9.1.2 (Compose 및 Testcontainers 동일 버전)
- Gradle은 저장소의 Wrapper를 사용합니다.

처음 설정할 때 `.env.example`을 `.env`로 복사하고 필요한 값을 설정합니다. 기존 `.env`는 덮어쓰지 않습니다.

```powershell
Copy-Item .env.example .env
docker compose up -d
```

IntelliJ에서 Project SDK와 Gradle JVM을 JDK 25로 설정하고 실행 프로필은 `local`을 사용합니다.
Compose는 `.env`를 자동으로 읽지만 Spring Boot는 자동으로 읽지 않습니다.
기본값을 변경했다면 실행 구성의 환경 변수에도 `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`, `POSTGRES_PORT`, `VALKEY_PORT`를 동일하게 지정합니다.
운영 환경은 `prod` 프로필과 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `VALKEY_HOST`, `VALKEY_PORT`를 사용합니다.

```powershell
# JAVA_HOME이 JDK 25를 가리키는 상태에서 실행합니다.
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

## 검증과 코드 포맷

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat clean build
```

`build`에는 Google Java 스타일 검사(`spotlessCheck`)와 테스트가 포함됩니다.
포매터 버전은 Gradle에 고정하며 IDE의 포맷 결과도 이 검사를 기준으로 맞춥니다.
테스트에는 Docker가 필요하고, 개발 DB와 별도의 임시 PostgreSQL/Valkey 컨테이너를 사용합니다.
GitHub Actions는 PR과 develop/master push 시 동일한 빌드를 실행합니다.

## API 문서

local 프로필로 실행한 뒤 아래 주소를 사용합니다.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- 공통 응답 계약: [docs/api-contract.md](docs/api-contract.md)

Swagger에는 실제 구현한 `/api/v1/**` 경로만 표시합니다.
`GET /api/v1/characters/search?characterName=캐릭터명`으로 프로필·전투정보·원정대를 검색할 수 있습니다.
실제 조회에는 IntelliJ 실행 구성이나 서버 환경 변수에 `LOSTARK_API_KEY`를 설정해야 합니다.
`.env`에만 작성하면 Spring Boot에는 전달되지 않습니다. 키 없이도 문서와 테스트는 실행됩니다.
검색 동작과 오류 코드는 [docs/character-search.md](docs/character-search.md)를 참고합니다.
원본 저장·중복 억제·트랜잭션 정책은 [docs/character-snapshots.md](docs/character-snapshots.md)에 정리했습니다.
저장된 당시 프로필 수치는 `GET /api/v1/characters/snapshots`로 조회하며 [이력 조회 계약](docs/character-history.md)을 참고합니다.
관측 저장·이력 조회는 `CHARACTER_SNAPSHOTS_ENABLED=false`가 기본값이며 이력 API는 503을 반환합니다.
검색·단기 캐시는 동작합니다. 합성 데이터 개발 또는 보관·재제공 범위를 확인한 환경에서만
실행 환경 변수로 `CHARACTER_SNAPSHOTS_ENABLED=true`를 지정합니다. [검증 근거와 한계](docs/source-verification.md)를 참고합니다.
관측값 집계와 미확인 값 처리 기준은 [docs/character-analysis.md](docs/character-analysis.md)를 참고합니다.
원천의 null·빈 목록 구분과 캐시 호환성은 [docs/character-observation.md](docs/character-observation.md)를 참고합니다.
관측 근거 기반 진단과 프론트 연동 계약은 [docs/character-diagnostics.md](docs/character-diagnostics.md)에 정리했습니다.
기준서의 비교 사이트 10곳과 검증 범위는 [docs/reference-sites.md](docs/reference-sites.md)에서 확인할 수 있습니다.
단일 인스턴스 운영·캐시 장애 정책과 Micrometer 지표는 [docs/backend-operations.md](docs/backend-operations.md)에 정리했습니다.
거래소 현재 판매·최근 거래 가격은 `/api/v1/markets/items`와 `/api/v1/markets/trades`로 조회합니다.
검색 조건·가격 출처·조회 시각과 제한은 [docs/market-prices.md](docs/market-prices.md)를 참고합니다.
거래소 선택 목록은 `/api/v1/markets/options`로 조회합니다. 기본 가격 캐시 30초·옵션 캐시 10분과
동일 요청 병합·캐시 장애 정책은 [docs/market-cache.md](docs/market-cache.md)에 정리했습니다.
운영 프로필은 기본 비활성화하며 필요한 환경에서 `API_DOCS_ENABLED=true`로 활성화합니다.
Lost Ark API 키는 서버 설정으로만 사용하며 Swagger에 입력하거나 노출하지 않습니다.

`build`의 통합 테스트는 `build/generated/openapi.json`과 응답 검증용 JSON을 생성합니다.
GitHub Actions는 Gradle의 Java 테스트를 실행하고 명세와 응답을 `openapi-contract` 산출물로 보관합니다.

응답 필드는 null이어도 생략하지 않습니다. 상세한 명세 보정과 검증 범위는
[docs/openapi-contract.md](docs/openapi-contract.md)를 참고합니다.

## 실제 Lost Ark API 검증

공식 한도는 분당 100회이며 서버는 여유를 둔 90회/분을 기본 예산으로 사용합니다.
아래 실조회 테스트는 독립 Testcontainers DB·Valkey에서 한 캐릭터만 조회하며,
외부 호출 예산을 2회로 제한하고 재조회에서 추가 호출이 없는지 확인합니다.
실조회 테스트는 관측 저장을 비활성화하며 스냅샷이 생성되지 않는지 확인합니다.
기본 빌드와 CI에서는 이 테스트를 건너뜁니다. 실제 429를 유발하는 부하 테스트는 하지 않습니다.

현재 터미널의 `LOSTARK_API_KEY`를 설정한 상태에서 실행합니다.
IntelliJ 실행 구성의 환경 변수나 `.env`는 터미널에 자동 전달되지 않습니다.
`.env.example`의 키는 빈 값으로 유지합니다.

```powershell
$env:LOSTARK_LIVE_TEST = 'true'
$env:LOSTARK_TEST_CHARACTER = '조회할캐릭터명'
try {
    .\gradlew.bat test --tests com.loakraken.LostArkLiveIntegrationTest
    if ($LASTEXITCODE -ne 0) { throw '실조회 테스트 실패' }
} finally {
    Remove-Item Env:LOSTARK_LIVE_TEST, Env:LOSTARK_TEST_CHARACTER -ErrorAction SilentlyContinue
}
```

실제 캐릭터 응답은 파일로 남기지 않습니다. 공개 OpenAPI 명세만 `build/generated/openapi-live.json`에 저장합니다.
이전에 생성된 로컬 응답 파일은 이 변경으로 삭제하지 않습니다.
