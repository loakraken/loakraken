# 운영 배포 절차 (초안)

운영 서버·도메인은 아직 정해지지 않았다. 이 문서는 사업자와 무관한 "Linux 서버 1대 + docker compose" 기준이며, 서버가 정해지면 그대로 적용한다. 서버 계정 생성·결제·DNS 변경·비밀값 등록은 사용자가 직접 한다.

## 구성

```
인터넷 ─80/443→ edge(Caddy, HTTPS 자동) ─public망→ web(Next.js) ─internal망→ api(Spring Boot) → postgres, valkey
                                                                               └→ 로스트아크 공식 API(외부)
```

- 호스트에 여는 포트는 edge의 80/443뿐이다. web·api·postgres·valkey는 포트를 열지 않는다.
- 브라우저는 백엔드를 직접 부르지 않는다. Next 서버가 `http://api:8080`으로 부른다.
- 클라이언트 IP: Caddy가 바깥의 `X-Forwarded-For`를 버리고 실제 접속 주소로 다시 쓴다. Next가 이 값을 백엔드로 넘기고, 백엔드는 Docker 내부망(사설 대역)에서 온 헤더만 믿는다(Tomcat RemoteIpValve 기본값).
  - 로컬(Docker Desktop)에서 위조 헤더 `6.6.6.6`이 버려지고 접속 주소로 바뀌는 것을 확인했다. 다만 Docker Desktop은 접속 주소가 게이트웨이(172.x)로 보인다.
  - Linux 서버는 외부 접속의 원래 IP가 유지된다(iptables DNAT). **서버를 처음 띄우면 백엔드 로그·유입 제한 키에 실제 클라이언트 IP가 찍히는지 확인한다.** 게이트웨이 주소만 보이면 edge를 `network_mode: host`로 바꾸는 것을 검토한다.

| 파일 | 역할 |
|---|---|
| `Dockerfile` | 백엔드 이미지(Temurin 25 JRE, 비루트 uid 10001, 힙 = 컨테이너 메모리의 75%) |
| `deploy/compose.prod.yaml` | 운영 스택 |
| `deploy/Caddyfile` | HTTPS·보안 헤더·web 프록시 |
| `deploy/stack.env.example` | 비밀이 아닌 스택 설정 예시 |
| `.github/workflows/image.yml` | 이미지 빌드(PR은 빌드만, push는 `PUBLISH_IMAGES=true`일 때 GHCR 업로드) |
| `.github/workflows/deploy.yml` | 수동 배포(환경 선택 + 이미지 태그) |

프론트 이미지는 `loakraken-web` 저장소의 `Dockerfile`·`image.yml`이 만든다.

## 비밀값

운영 비밀값은 **서버의 비밀 파일에만** 둔다. 저장소·이미지·빌드 인자·로그·문서에 두지 않는다.

| 파일(`/opt/loakraken/secrets/`) | 쓰는 컨테이너 | 내용 |
|---|---|---|
| `db_password` | postgres, api | DB 암호(새로 생성) |
| `valkey_password` | valkey, api | Valkey 암호(새로 생성) |
| `lostark_api_key` | api | **운영 전용** 로스트아크 키(로컬 개발 키와 다름) |

- compose secrets로 각 컨테이너의 `/run/secrets/`에 파일로만 마운트한다. 환경변수에 들어가지 않아 `docker inspect`에 보이지 않는다.
- 백엔드는 `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`로 파일 이름(`DB_PASSWORD`, `LOSTARK_API_KEY`, `spring.data.redis.password`)을 설정 키로 읽는다.
- 권한: 디렉터리는 root 전용(700), 파일은 444. 컨테이너 사용자(postgres·valkey·uid 10001)가 읽어야 하므로 파일은 읽기 허용, 디렉터리로 호스트의 다른 사용자를 막는다.
- 남은 한계: Valkey 암호는 `valkey-server --requirepass` 인자로 넘겨 컨테이너 안 프로세스 목록에 보인다. 필요하면 ACL 파일 방식으로 바꾼다.

서버에서 사용자가 직접 실행한다(값은 화면에 출력하지 않는다).

```bash
sudo install -d -m 700 /opt/loakraken/secrets
openssl rand -base64 32 | tr -d '\n' | sudo tee /opt/loakraken/secrets/db_password > /dev/null
openssl rand -base64 32 | tr -d '\n' | sudo tee /opt/loakraken/secrets/valkey_password > /dev/null
sudo sh -c 'umask 077; cat > /opt/loakraken/secrets/lostark_api_key'   # 키를 붙여 넣고 Ctrl+D
sudo chmod 444 /opt/loakraken/secrets/*
```

## 서버 최초 준비

1. Ubuntu LTS 등 Linux 서버, Docker Engine + compose 플러그인 설치
2. 방화벽: 22(관리자 IP만), 80, 443만 허용
3. 배포 전용 사용자(`deploy`)를 만들고 `docker` 그룹에 넣는다. GitHub Actions용 SSH 키를 등록한다.
4. `/opt/loakraken/production/stack.env`를 `deploy/stack.env.example`에서 만든다. 운영은 `STATS_COLLECTOR_ENABLED=true`, `MARKET_WARMUP_ENABLED=true`(1곳만).
5. 위 "비밀값" 파일을 만든다.
6. GHCR 읽기: 사용자가 `read:packages` 권한만 있는 토큰으로 서버에서 `docker login ghcr.io`를 한 번 한다.
7. GitHub 저장소 설정(사용자)
   - Environments `production`(승인자 지정), `staging`
   - 환경 비밀값: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`
   - 저장소 변수 `PUBLISH_IMAGES=true`(두 저장소). 이전에는 이미지가 GHCR에 올라가지 않는다.
8. 도메인 DNS A/AAAA 레코드를 서버로 향하게 한다. `SITE_ADDRESS`에 도메인을 넣으면 Caddy가 인증서를 받는다.

## 배포

1. develop/master에 병합되면 `image.yml`이 `sha-<커밋7자>` 태그로 두 이미지를 올린다.
2. Actions → Deploy → 환경·`api_tag`·`web_tag`를 넣고 실행한다. production은 승인 후 진행된다.
3. 워크플로가 하는 일: compose·Caddyfile 업로드 → 직전 태그를 `images.prev.env`로 보관 → DB 백업(`backups/pre-deploy-*.dump`, 최근 14개 유지) → `pull` → `up -d --wait`(헬스 체크 통과까지 대기)
4. 배포 후 15분 관찰: 오류율, `loakraken.lostark.gate{event=upstream_429}`, 응답 지연

## 롤백

```bash
cd /opt/loakraken/production
cp images.prev.env images.env
docker compose -f compose.prod.yaml --env-file stack.env --env-file images.env up -d --wait
```

DB 마이그레이션(Flyway)은 되돌리지 않는다. 스키마를 바꾸는 배포는 이전 버전이 새 스키마에서도 동작하는지 PR에서 확인한다.

## 공식 API 호출 한도와 배포

- 운영 키 1개 = 90회/분(공식 100, 여유 10). 운영 스택의 모든 백엔드가 나눠 쓴다.
- 현재 `up -d`는 api 컨테이너를 교체하므로 구·신이 겹치지 않는다(수십 초 중단). 겹쳐 띄우는 무중단 배포는 공유 호출 게이트(#107)가 들어온 뒤에만 한다.
- 수집기·예열은 `stack.env`에서 운영 1곳만 켠다.

## 로컬 점검

임시 비밀 파일과 로컬 이미지로 운영 스택을 띄워 볼 수 있다(공식 API 키 없이, 호출 0회).

```bash
docker build -t loakraken-api:local .
docker build -t loakraken-web:local ../loakraken-web
mkdir -p /tmp/lk-secrets && cd /tmp/lk-secrets
printf local-db > db_password; printf local-valkey > valkey_password; : > lostark_api_key
cd -
SECRETS_DIR=/tmp/lk-secrets SITE_ADDRESS=:80 EDGE_HTTP_PORT=8096 EDGE_HTTPS_PORT=8443 \
API_IMAGE=loakraken-api:local WEB_IMAGE=loakraken-web:local \
docker compose -p lk-prod-check -f deploy/compose.prod.yaml up -d --wait
curl -s http://127.0.0.1:8096/about -o /dev/null -w '%{http_code}\n'
docker compose -p lk-prod-check -f deploy/compose.prod.yaml down -v
```
