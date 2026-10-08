# 운영 배포 절차 (초안)

운영 서버는 아직 정해지지 않았다. 도메인은 `loakraken.gg`로 정했다(구입 전). 이 문서는 사업자와 무관한 "Linux 서버 1대 + docker compose" 기준이며, 서버가 정해지면 그대로 적용한다. 서버 계정 생성·결제·DNS 변경·비밀값 등록은 사용자가 직접 한다.

## 구성

```
인터넷 ─80/443→ edge(Caddy) ─public망→ web(Next.js) ─internal망→ api(Spring Boot) ─data망→ postgres, valkey
                                                                     └→ 로스트아크 공식 API(외부)
```

| 망 | 연결 | 외부 통신 |
|---|---|---|
| public | edge ↔ web | 허용(edge가 인터넷을 받음) |
| internal | web ↔ api | 허용(api가 공식 API를 부름). 서브넷 고정 |
| data | api ↔ postgres·valkey | **차단**(`internal: true`). DB·캐시는 인터넷에 나가지 않는다 |

- 호스트에 여는 포트는 edge의 80/443뿐이다. web·api·postgres·valkey는 포트를 열지 않는다.
- 브라우저는 백엔드를 직접 부르지 않는다. Next 서버가 `http://api:8080`으로 부른다.

### 클라이언트 IP와 유입 제한

- Caddy가 바깥의 `X-Forwarded-For`를 버리고 실제 접속 주소로 다시 쓴다(trusted_proxies 미설정). Next(web #78)가 이 값을 가공 없이 백엔드로 넘긴다.
- 백엔드는 **web의 내부 고정 주소 하나**에서 온 헤더만 믿는다. 사설 대역 전체를 믿지 않는다.
  - internal망을 `INTERNAL_SUBNET`(운영 기본 172.31.250.0/24)으로 고정하고, web에 `WEB_INTERNAL_IP`(.10)를 준다.
  - api는 `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`(=`TRUSTED_PROXY_REGEX`, `172\.31\.250\.10`)로 그 주소만 믿는다. 셋은 함께 바꾼다.
  - RemoteIpValve 자체는 백엔드 F-10 PR에서 켠다(`server.forward-headers-strategy: native`). 그 전까지 이 설정은 효과가 없다.
- 위조 방지 배포 조건(PM-프론트·PM-백엔드 합의)
  1. Next를 인터넷에 직접 노출하지 않는다. edge만 web에 닿는다.
  2. 신뢰 프록시는 web 고정 주소 하나다(위).
  3. 백엔드 8080은 호스트에 열지 않는다. edge는 internal망에 없어 api에 닿지 않는다.
- 로컬 확인(Docker Desktop)
  - 위조 `X-Forwarded-For: 6.6.6.6`을 보내면 edge → standalone web → 백엔드 경로에서 백엔드는 edge가 본 접속 주소 하나만 받는다.
  - edge 컨테이너에서는 `api` 이름이 해석되지 않는다.
  - Docker Desktop은 접속 주소가 게이트웨이(172.x)로 보인다. Linux 서버는 외부 접속의 원래 IP가 유지된다(iptables DNAT). **서버를 처음 띄우면 실제 클라이언트 IP가 백엔드에 닿는지 확인한다.** 게이트웨이 주소만 보이면 edge를 `network_mode: host`로 바꾸는 것을 검토한다.

## 로그 보존

- 컨테이너 로그는 서비스마다 10MB × 5개까지만 남긴다(`json-file` 회전).
- edge 접속 로그는 켜지 않았다(Caddyfile에 `log` 없음). 클라이언트 IP 원본을 로그로 남기지 않는다.
- IP는 유입 제한 판정에만 쓰고 원본은 저장하지 않는다(백엔드 기준: 일별 솔트 해시).

## 파일

| 파일 | 역할 |
|---|---|
| `Dockerfile` | 백엔드 이미지(Temurin 25 JRE, 비루트 uid 10001, 힙 = 컨테이너 메모리의 75%) |
| `deploy/compose.prod.yaml` | 운영·스테이징 공용 스택 정의(환경별 값은 stack.env) |
| `deploy/Caddyfile` | HTTPS·보안 헤더·web 프록시 |
| `deploy/stack.production.env.example` | 운영 스택 설정 예시(비밀 아님) |
| `deploy/stack.staging.env.example` | 스테이징 스택 설정 예시(비밀 아님) |
| `.github/workflows/image.yml` | 이미지 빌드(PR은 빌드 확인만, push는 `PUBLISH_IMAGES=true`일 때 GHCR 업로드) |
| `.github/workflows/deploy.yml` | 수동 배포(환경 선택 + 이미지 태그, 운영은 `sha-` 태그만) |

프론트 이미지는 `loakraken-web` 저장소의 `Dockerfile`·`image.yml`이 만든다.

## 환경과 키 규칙

| 환경 | 서버 경로 | 로스트아크 키 | 수집기·예열 | 공개 |
|---|---|---|---|---|
| production | `/opt/loakraken/production` | **운영 키**("LOAKraken Production")만 | true(이 스택 1곳만) | `loakraken.gg` 80/443 |
| staging | `/opt/loakraken/staging` | **개발 키**(기존 "LOAKraken" 클라이언트) | **false 고정** | 서버 안 127.0.0.1:8081, SSH 터널로 접속 |

- 공식 한도는 클라이언트(키)별 100회/분이다. 키가 다르면 Valkey가 달라도 운영 예산과 섞이지 않는다.
- 운영 키는 운영 스택의 비밀 파일에만 둔다. 스테이징·로컬에 넣지 않는다.
- 개발 키는 로컬 수집기(분당 약 20회)와 다른 팀 로컬 인스턴스도 함께 쓴다. 그래서 스테이징 점검 중 429가 날 수 있다. 운영에는 영향이 없다.
- 출시 전에 필요하면 스테이징 전용 클라이언트를 다시 검토한다(계정당 최대 5개). 여러 키를 돌려 써서 한도를 우회하지 않는다.
- 같은 서버에 두 스택을 올리므로 `STACK_NAME`, `SECRETS_DIR`, `INTERNAL_SUBNET`·`WEB_INTERNAL_IP`·`TRUSTED_PROXY_REGEX`, edge 포트가 서로 달라야 한다(예시 파일에 반영).

## 비밀값

비밀값은 **스택별 서버 비밀 파일에만** 둔다. 저장소·이미지·빌드 인자·로그·문서에 두지 않는다. `SECRETS_DIR`은 필수이며 기본값이 없다(스택이 서로의 파일을 읽지 않게).

| 파일(`/opt/loakraken/<환경>/secrets/`) | 쓰는 컨테이너 | 내용 |
|---|---|---|
| `db_password` | postgres, api | DB 암호(새로 생성) |
| `valkey_password` | valkey, api | Valkey 암호(새로 생성) |
| `lostark_api_key` | api | 운영은 운영 키, 스테이징은 개발 키 |

- compose secrets로 각 컨테이너의 `/run/secrets/`에 파일로만 마운트한다. 환경변수에 들어가지 않아 `docker inspect`에 보이지 않는다.
- 백엔드는 `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`로 파일 이름(`DB_PASSWORD`, `LOSTARK_API_KEY`, `spring.data.redis.password`)을 설정 키로 읽는다.
- 권한: 디렉터리는 root 전용(700), 파일은 444. 컨테이너 사용자(postgres·valkey·uid 10001)가 읽어야 하므로 파일은 읽기 허용, 디렉터리로 호스트의 다른 사용자를 막는다.
- 남은 한계: Valkey 암호는 `valkey-server --requirepass` 인자로 넘겨 컨테이너 안 프로세스 목록에 보인다. 필요하면 ACL 파일 방식으로 바꾼다.

서버에서 사용자가 직접 실행한다(값은 화면에 출력하지 않는다). `ENV`는 `production` 또는 `staging`.

```bash
ENV=production
sudo install -d -m 700 /opt/loakraken/$ENV/secrets
openssl rand -base64 32 | tr -d '\n' | sudo tee /opt/loakraken/$ENV/secrets/db_password > /dev/null
openssl rand -base64 32 | tr -d '\n' | sudo tee /opt/loakraken/$ENV/secrets/valkey_password > /dev/null
sudo sh -c "umask 077; cat > /opt/loakraken/$ENV/secrets/lostark_api_key"   # 환경에 맞는 키를 붙여 넣고 Ctrl+D
sudo chmod 444 /opt/loakraken/$ENV/secrets/*
```

## 서버 최초 준비

1. Ubuntu LTS 등 Linux 서버, Docker Engine + compose 플러그인 설치
2. 방화벽: 22(관리자 IP만), 80, 443만 허용
3. 배포 전용 사용자(`deploy`)를 만들고 `docker` 그룹에 넣는다. GitHub Actions용 SSH 키를 등록한다.
4. `/opt/loakraken/production/stack.env`를 `deploy/stack.production.env.example`에서, `/opt/loakraken/staging/stack.env`를 `deploy/stack.staging.env.example`에서 만든다.
5. 위 "비밀값" 파일을 환경별로 만든다.
6. GHCR 읽기: 사용자가 `read:packages` 권한만 있는 토큰으로 서버에서 `docker login ghcr.io`를 한 번 한다.
7. GitHub 저장소 설정(사용자)
   - Environments `production`(승인자 지정), `staging`
   - 환경 비밀값: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`
   - 저장소 변수 `PUBLISH_IMAGES=true`(두 저장소). 이전에는 이미지가 GHCR에 올라가지 않는다.
8. DNS: `loakraken.gg`의 A(그리고 IPv6가 있으면 AAAA) 레코드를 서버 IP로 둔다. `SITE_ADDRESS=loakraken.gg`이면 Caddy가 Let's Encrypt 인증서를 자동 발급·갱신한다(80/443이 열려 있어야 함).

## 배포

1. develop/master에 병합되면 `image.yml`이 `sha-<커밋7자>` 태그로 두 이미지를 올린다.
2. Actions → Deploy → 환경·`api_tag`·`web_tag`를 넣고 실행한다. production은 승인 후 진행되며 `sha-` 태그만 받는다.
3. 워크플로가 하는 일: compose·Caddyfile 업로드 → 직전 태그를 `images.prev.env`로 보관 → DB 백업(`backups/pre-deploy-*.dump`, 최근 14개 유지) → `pull` → `up -d --wait`(헬스 체크 통과까지 대기)
4. 배포 후 15분 관찰: 오류율, `loakraken.lostark.gate{event=upstream_429}`, 응답 지연

배포 전 백업은 같은 서버 디스크에만 있다. 서버를 잃으면 함께 잃으므로 외부 정기 백업은 별도로 준비한다(출시 점검표 B6).

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
- 수집기·예열은 운영 stack.env에서만 true다.

## 로컬 점검

임시 비밀 파일과 로컬 이미지로 운영 스택을 띄워 볼 수 있다(공식 API 키 없이, 호출 0회).

```bash
docker build -t loakraken-api:local .
docker build -t loakraken-web:local ../loakraken-web
mkdir -p /tmp/lk-secrets && cd /tmp/lk-secrets
printf local-db > db_password; printf local-valkey > valkey_password; : > lostark_api_key
cd -
SECRETS_DIR=/tmp/lk-secrets SITE_ADDRESS=:80 EDGE_HTTP_PORT=127.0.0.1:8096 EDGE_HTTPS_PORT=127.0.0.1:8443 \
API_IMAGE=loakraken-api:local WEB_IMAGE=loakraken-web:local \
docker compose -p lk-prod-check -f deploy/compose.prod.yaml up -d --wait
curl -s http://127.0.0.1:8096/about -o /dev/null -w '%{http_code}\n'
docker compose -p lk-prod-check -f deploy/compose.prod.yaml down -v
```
