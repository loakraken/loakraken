# 운영 배포 절차 (초안)

운영 구성은 **AWS Lightsail 서울 4GB + 앞단 Cloudflare 무료 프록시 + DB 외부 백업 Cloudflare R2**다(사용자 결정 2026-10-09). 도메인은 `loakraken.gg`(Porkbun 등록, DNS는 Cloudflare로 옮김). Cloudflare 관련 설정은 [cloudflare.md](cloudflare.md)를 따른다. 이 문서의 compose·배포 절차는 사업자와 무관하게 쓴다. 서버 계정 생성·결제·DNS 변경·비밀값 등록은 사용자가 직접 한다.

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

## 헬스 체크와 사이트 주소 (#129)

| 대상 | 주소 | 용도 |
|---|---|---|
| api liveness | `:9090/actuator/health/liveness` | 프로세스 생존. DB·Valkey가 멈춰도 UP(불필요한 재시작 방지) |
| api readiness | `:9090/actuator/health/readiness` | DB·Valkey 포함. compose 헬스 체크와 `up --wait`가 이 값을 본다. Valkey 정지 시 503 확인 |
| web | `:3000/about` | 백엔드를 부르지 않는 경로 |

- 관리(actuator) 포트 9090은 앱 포트 8080과 나눴다. 8080에는 actuator가 없다(404). 9090도 health만 노출한다(지표는 백엔드가 prometheus 레지스트리를 넣은 뒤 연다).
- web의 `SITE_URL`은 필수다. 운영 `https://loakraken.gg`이면 robots가 허용하고 sitemap·canonical이 이 주소를 쓴다. 스테이징(`http://127.0.0.1:8081`)은 `Disallow: /`다. 같은 이미지를 쓰고 값은 실행할 때만 넣는다.
- 백엔드 로그는 ECS JSON(`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)이다. 로컬 확인에서 클라이언트 IP는 찍히지 않았다.

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
| `deploy/Dockerfile.bundle` | 배포 번들 이미지 `loakraken-deploy`(compose·Caddyfile·cloudflare·backup·pull 파일을 담음) |
| `deploy/pull/pull-deploy.sh` | 서버가 채널 태그를 따라가 배포하고, 실패하면 되돌린다 |
| `deploy/pull/loakraken-pull-deploy@.service`·`.timer` | 위 스크립트를 2분마다 실행(systemd, `@production`·`@staging`) |
| `.github/workflows/image.yml` | 이미지 빌드(PR은 빌드 확인만, push는 `PUBLISH_IMAGES=true`일 때 GHCR에 api·배포 번들 업로드) |
| `.github/workflows/deploy.yml` | 배포 승인(환경 선택 + 이미지 태그 → GHCR 채널 태그 이동, 운영은 `sha-` 태그만). 서버에 접속하지 않는다 |

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
| `slack_webhook_alerts` | (컨테이너 아님) pull 배포 스크립트 | Slack #alerts Incoming Webhook URL. 없으면 알림만 건너뛴다 |

- compose secrets로 각 컨테이너의 `/run/secrets/`에 파일로만 마운트한다. 환경변수에 들어가지 않아 `docker inspect`에 보이지 않는다.
- 백엔드는 `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`로 파일 이름(`DB_PASSWORD`, `LOSTARK_API_KEY`, `spring.data.redis.password`)을 설정 키로 읽는다.
- 권한: 디렉터리는 root 전용(700), 파일은 444. 컨테이너 사용자(postgres·valkey·uid 10001)가 읽어야 하므로 파일은 읽기 허용, 디렉터리로 호스트의 다른 사용자를 막는다.
- Valkey 암호는 프로세스 인자에 넣지 않는다. 시작할 때 컨테이너 안 `/tmp/valkey.conf`(valkey 그룹만 읽기)에 쓰고 `valkey` 사용자로 실행한다. 헬스 체크도 `-a` 대신 `VALKEYCLI_AUTH` 환경 변수로 인증한다(#119). `/tmp/valkey.conf`는 컨테이너 쓰기 층에 남는다(root:valkey 640). 컨테이너를 다시 만들면 사라지며, 호스트의 다른 사용자는 읽을 수 없다.

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
2. 방화벽(Lightsail): 443은 Cloudflare 대역만, 80은 닫음, 22는 관리자 IP만 + "Allow Lightsail browser SSH/RDP" 선택. 자세한 내용은 [cloudflare.md](cloudflare.md) "Lightsail 방화벽".
3. SSH 설정: 키 인증만 쓴다. `/etc/ssh/sshd_config.d/90-loakraken.conf`에 `PasswordAuthentication no`, `PermitRootLogin no`를 넣고 `sudo sshd -t && sudo systemctl reload ssh`. 브라우저 SSH는 Lightsail이 관리하는 키로 기본 사용자(ubuntu)에 접속하므로 이 설정과 함께 쓸 수 있다. 서버에 배포용 SSH 키는 두지 않는다(GitHub가 서버에 접속하지 않음).
4. `/opt/loakraken/production/stack.env`를 `deploy/stack.production.env.example`에서, `/opt/loakraken/staging/stack.env`를 `deploy/stack.staging.env.example`에서 만든다. `DEPLOY_CHANNEL`·`DEPLOY_REGISTRY`가 들어 있는지 확인한다.
5. 위 "비밀값" 파일을 환경별로 만든다. Slack 알림을 받으려면 `secrets/slack_webhook_alerts`(웹훅 URL 한 줄, 444)도 만든다.
6. GHCR 읽기: 사용자가 `read:packages` 권한만 있는 토큰으로 서버에서 `sudo docker login ghcr.io`를 한 번 한다. pull 배포는 root로 돌므로 root 계정에 로그인해야 한다.
7. GitHub 설정(사용자)
   - loakraken 저장소 Environments: `production`(필수 승인자 = 사용자), `staging`
   - 저장소 변수 `PUBLISH_IMAGES=true`(두 저장소). 이전에는 이미지가 GHCR에 올라가지 않는다.
   - `loakraken-web` 패키지 설정 → Manage Actions access에 `loakraken` 저장소를 **Write**로 추가한다. Deploy 워크플로가 web 이미지의 채널 태그를 옮기려면 필요하다.
   - SSH 관련 비밀값(`DEPLOY_HOST` 등)은 만들지 않는다.
8. DNS: Porkbun에서 네임서버를 Cloudflare로 바꾸고, Cloudflare에서 `loakraken.gg` A(그리고 IPv6가 있으면 AAAA) 레코드를 Lightsail 고정 IP로 **프록시 켬(주황 구름)**으로 둔다. SSL 모드는 Full(strict), 원 서버 인증서는 Cloudflare Origin CA(`secrets/tls/`). `stack.env`의 `CADDYFILE=Caddyfile.cloudflare`. 자세한 내용은 [cloudflare.md](cloudflare.md).
9. 첫 배포 승인(아래 "배포" 1~2) 뒤 pull 배포를 설치한다(아래 "pull 배포 설치").

## 배포 (서버가 가져오는 방식)

GitHub는 서버에 접속하지 않는다. 승인된 릴리스만 GHCR 채널 태그(`:production`·`:staging`)로 옮기고, 서버가 그 태그를 따라간다(PM 총괄 결정 2026-10-09).

```
병합 → image.yml: sha- 태그로 api·web·배포 번들 업로드
     → Actions › Deploy 실행(환경·태그 입력) → production은 사용자 승인 → 채널 태그 이동
     → 서버 타이머(2분) → 태그가 바뀌었으면 배포 → Slack #alerts에 결과
```

1. develop/master에 병합되면 `image.yml`이 `sha-<커밋7자>` 태그로 api·배포 번들(`loakraken-deploy`)을, web 저장소의 `image.yml`이 web 이미지를 올린다.
2. Actions → Deploy → 환경·`api_tag`·`web_tag`를 넣고 실행한다. production은 GitHub Environments 승인 후에만 진행되며 `sha-` 태그만 받는다. 배포 번들은 `api_tag`와 같은 커밋을 쓴다. 실행 요약에 옮긴 digest가 남는다.
3. 서버의 `loakraken-pull-deploy@<환경>.timer`가 2분 안에 바뀐 태그를 본다. `pull-deploy.sh`가 하는 일:
   1. 채널 태그 3개를 받아 digest를 확인한다. `.deployed`와 같으면 끝, `.failed`(실패했던 릴리스)와 같으면 다시 시도하지 않는다.
   2. 배포 번들을 풀어 compose·Caddyfile·cloudflare·backup·pull 파일을 바꾼다(직전 파일은 `.bundle.prev`에 보관).
   3. DB 백업(`backups/pre-deploy-*.dump`, 최근 14개 유지)
   4. 직전 이미지를 `images.prev.env`로 보관하고 `images.env`에 **digest로 고정**한 이미지를 쓴다.
   5. `up -d --wait`(헬스 체크 통과까지 최대 5분). 성공하면 `.deployed`·`deploy-history.log`에 남기고 Slack에 알린다.
   6. 실패하면 직전 이미지·번들로 되돌리고 `.failed`에 남긴 뒤 Slack에 알린다.
4. 배포 후 15분 관찰: 오류율, `loakraken.lostark.gate{event=upstream_429}`, 응답 지연

배포 전 백업은 같은 서버 디스크에만 있다. 서버를 잃으면 함께 잃으므로 외부 정기 백업은 [backup.md](backup.md)를 따른다(출시 점검표 B6).

### pull 배포 설치 (환경마다 1회)

첫 배포 승인으로 채널 태그가 생긴 뒤 서버에서 사용자가 실행한다. 이후 `pull/` 파일은 배포 번들로 자동 갱신된다(systemd 유닛 파일 자체가 바뀐 경우에만 아래 `cp`·`daemon-reload`를 다시 한다).

```bash
ENV=production
cd /opt/loakraken/$ENV
cid=$(sudo docker create ghcr.io/loakraken/loakraken-deploy:$ENV true)
sudo docker cp "$cid:/bundle/pull" . && sudo docker rm "$cid"
sudo cp pull/loakraken-pull-deploy@.service pull/loakraken-pull-deploy@.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start loakraken-pull-deploy@$ENV.service     # 첫 배포를 바로 실행(결과는 아래 journalctl)
sudo systemctl enable --now loakraken-pull-deploy@$ENV.timer
journalctl -u loakraken-pull-deploy@$ENV.service -n 50
```

## 롤백

평소 롤백도 **승인 절차를 거친다**. Actions → Deploy를 이전 `sha-` 태그로 다시 실행한다(`deploy-history.log`·실행 요약에서 직전 태그를 찾는다). 서버가 2분 안에 그 릴리스로 돌아간다.

- 배포가 헬스 체크에서 실패하면 서버가 스스로 직전 릴리스로 되돌린다(위 3-6). 사람이 할 일은 원인 확인과 고친 릴리스 승인이다.
- 실패한 릴리스를 그대로 다시 시도해야 하면(예: 비밀 파일을 고친 뒤) 서버에서 `.failed`를 지운다.
- 긴급 수동 롤백(GitHub 장애 등, 관리자 SSH 또는 Lightsail 브라우저 SSH):

```bash
cd /opt/loakraken/production
sudo systemctl stop loakraken-pull-deploy@production.timer
sudo cp images.prev.env images.env
sudo docker compose -f compose.prod.yaml --env-file stack.env --env-file images.env up -d --wait
# 원인을 고친 릴리스를 Deploy로 승인한 뒤 타이머를 다시 켠다
sudo systemctl start loakraken-pull-deploy@production.timer
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
