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

## 작업 흐름

이슈 생성 및 LOAKraken Projects 연결 → develop 기준 이슈 번호 브랜치 → 구현·검증 → develop PR.
Projects 상태는 작업 시작 시 진행, PR 생성 시 검토, 병합 및 이슈 완료 시 완료로 갱신합니다.
커밋은 `<type>: <한글 변경 내용>`, PR은 `[TYPE] 제목` 형식입니다.
