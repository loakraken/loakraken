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

- 관리(actuator) 포트 9090은 앱 포트 8080과 나눴다. 8080에는 actuator가 없다(404). 9090은 health·prometheus만 노출한다(백엔드 #166). 지표 주소는 `http://api:9090/actuator/prometheus`(internal·data망 안에서만 닿고 edge는 닿지 않음). 수집기 동작 확인 예: `loakraken_background_last_success_seconds{job="collector"}`.
- web의 `SITE_URL`은 필수다. 운영 `https://loakraken.gg`이면 robots가 허용하고 sitemap·canonical이 이 주소를 쓴다. 스테이징(`http://127.0.0.1:8081`)은 `Disallow: /`다. 같은 이미지를 쓰고 값은 실행할 때만 넣는다.
- web 기능 플래그 `RECEIPT_TOOL`(원정대 영수증): 운영 `stack.env`는 `off`(PM 총괄 10-11, 계산 재대조 전까지). api `RECEIPT_ENABLED=false`와 함께 숨긴다. 스테이징도 api와 맞춰 off(계산 검증 때 둘 다 켬).
- 백엔드 기능 플래그(#214): `CRIT_SPEED_ENABLED`(치명 계산)·`GEM_ENABLED`(젬 가공)·`RECEIPT_ENABLED`(영수증)는 비우면 꺼짐이다. compose.prod.yaml이 api로 넘긴다. 스테이징은 `CRIT_SPEED_ENABLED=true`(web 치적 도구가 보이므로), 운영은 셋 다 `false`를 명시한다. 로컬 compose(8080 collector)는 치명 계산을 기본으로 켜고(`CRIT_SPEED_ENABLED:-true`), 영수증·젬 가공이나 계산 검증 8090 같은 따로 띄운 서버는 `.env`·실행 인자에 같은 변수를 `true`로 둔다.
- web 기능 플래그 `CRIT_SPEED_TOOL`(web #233): 운영 `stack.env`는 `off`를 명시해 치명 계산 도구를 첫 운영 배포에서 뺀다(PM 총괄 결정 10-09). 비우면 web 기본값(`SITE_URL`이 운영 주소일 때만 숨김)을 따르므로, `SITE_URL`이 빠지거나 달라도 숨겨지도록 명시한다. 스테이징은 비워 두어 보인다. 공개할 때 `on`으로 바꾸고 `up -d`.
- web 기능 플래그 `COMBAT_POWER_SIM`(전투력 시뮬레이터, web #365): 운영 `stack.env`는 `off`(PM 총괄·PM-프론트 10-10, 로아랩 대조 통과 전까지). 백엔드 `COMBAT_POWER_SIM_ENABLED=false`와 함께 이중으로 숨긴다. 스테이징은 비워 두어 보인다.
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
| `deploy/monitor/host-check.sh`·`loakraken-host-check@.service`·`.timer` | 서버 자원 점검(5분, 메모리·스왑·디스크·컨테이너 → 기록·Slack). "자원 점검" 참고 |
| `.github/workflows/ci.yml` | 빌드·테스트. develop·master push에서 **통과한 커밋만** `PUBLISH_IMAGES=true`일 때 GHCR에 api·배포 번들 업로드(publish job) |
| `.github/workflows/image.yml` | PR에서 이미지·번들 빌드 확인만 |
| `.github/workflows/deploy.yml` | 릴리스 지정(환경 선택 + 이미지 태그 → GHCR 채널 태그 이동, 운영은 `sha-` 태그와 확인 입력). 서버에 접속하지 않는다 |
| `deploy/pull/approve.sh` | **운영 승인**: 사용자가 서버에서 새 릴리스의 커밋·비교 링크를 보고 y/N으로 승인하면 바로 배포 |

프론트 이미지는 `loakraken-web` 저장소의 `Dockerfile`과 `frontend-ci.yml` publish job(검사 통과 뒤)이 만든다.

## 환경과 키 규칙

| 환경 | 서버 경로 | 로스트아크 키 | 수집기·예열 | 공개 |
|---|---|---|---|---|
| production | `/opt/loakraken/production` | **운영 키**("LOAKraken Production")만 | true(이 스택 1곳만) | `loakraken.gg` 80/443 |
| staging | `/opt/loakraken/staging` | **개발 키**(기존 "LOAKraken" 클라이언트) | **false 고정** | 서버 안 127.0.0.1:8081, SSH 터널로 접속 |

- 공식 한도는 클라이언트(키)별 100회/분이다. 키가 다르면 Valkey가 달라도 운영 예산과 섞이지 않는다.
- 운영 키는 운영 스택의 비밀 파일에만 둔다. 스테이징·로컬에 넣지 않는다.
- 개발 키는 로컬 수집기(분당 약 20회)와 다른 팀 로컬 인스턴스도 함께 쓴다. 그래서 스테이징 점검 중 429가 날 수 있다. 운영에는 영향이 없다.
- 출시 전에 필요하면 스테이징 전용 클라이언트를 다시 검토한다(계정당 최대 5개). 여러 키를 돌려 써서 한도를 우회하지 않는다.
- 같은 서버에 두 스택을 올리면 `STACK_NAME`, `SECRETS_DIR`, `INTERNAL_SUBNET`·`WEB_INTERNAL_IP`·`TRUSTED_PROXY_REGEX`, edge 포트가 서로 달라야 한다(예시 파일에 반영).
- **2GB 서버에서는 스테이징을 상시 띄우지 않는다**(아래 "메모리 예산"). 스테이징 점검은 로컬 점검(맨 아래)으로 하고, 서버에서 꼭 필요하면 운영 트래픽이 적은 시간에 잠깐 띄웠다가 `docker compose -p loakraken-staging ... down`으로 내린다. 4GB로 올린 뒤 상시 스테이징을 다시 검토한다.

## 메모리 예산 (Lightsail 2GB)

PM 총괄 결정(2026-10-09, 사용자): 자금 사정으로 **Lightsail 서울 2GB($12/월, 2 vCPU, 60GB SSD, 전송 3TB)** 로 시작하고, 부족이 실측되면 4GB($24)로 올린다. 공식 가격: https://aws.amazon.com/lightsail/pricing/ (2026-10-09 확인, 스냅숏 $0.05/GB-월).

| 서비스 | 컨테이너 한도(기본값) | 안쪽 상한 | 로컬 실측(유휴·가벼운 요청, 10-09) |
|---|---|---|---|
| api | `API_MEM_LIMIT` 768m | JVM 힙 65% ≈ 500MB(`API_JAVA_OPTS`) | 336MB |
| web | `WEB_MEM_LIMIT` 384m | Node 힙 256MB(`WEB_NODE_OPTIONS`) | 72MB |
| postgres | `POSTGRES_MEM_LIMIT` 384m | 기본 shared_buffers 128MB | 44MB |
| valkey | `VALKEY_MEM_LIMIT` 128m | `maxmemory` 96MB(`VALKEY_MAXMEMORY`), 만료 키 중 덜 쓴 것부터 지움(백엔드 키는 거의 모두 TTL이 있어 사실상 "덜 쓴 키부터". 게이트 창·blocked-until·수집기 임대는 자주 읽혀 지워질 가능성이 낮다. 시세 예열 임대는 9분에 한 번 읽어 지워질 수 있지만 운영이 1인스턴스라 영향 없음. noeviction은 가득 차면 캐시·게이트 503으로 사이트가 멈춰 쓰지 않는다, PM-백엔드 #162 검토) | 4MB |
| edge(Caddy) | `EDGE_MEM_LIMIT` 128m | | 13MB |
| 합계 | 한도 합 약 1.8GB | | 약 470MB |

- OS·Docker·systemd 몫으로 약 300MB를 남긴다. 한도 합(1.8GB)은 동시에 모두 차지 않는다는 전제이고, 넘치는 순간은 아래 스왑 2GB가 받는다.
- 실측은 공식 API 키 없이(호출 0회) 로컬에서 운영 이미지로 잰 값이다. 운영에서는 수집기·캐시로 api가 더 커진다. 그래서 아래 "자원 점검" 기록으로 1~2주 지켜본다.
- 한도는 `stack.env`에서 바꾼다(위 변수). 4GB로 올리면 `API_MEM_LIMIT=1280m`, `WEB_MEM_LIMIT=768m`, `POSTGRES_MEM_LIMIT=768m`, `VALKEY_MEM_LIMIT=256m`(10-09 이전 값)로 되돌린다.

### 스왑 2GB (서버 최초 준비 때 1회)

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/90-loakraken-swap.conf
sudo sysctl --system
swapon --show && free -h
```

`vm.swappiness=10`: 메모리가 정말 모자랄 때만 스왑을 쓴다. 스왑이 계속 쓰이면 느려지므로 증설 신호로 본다.

### 자원 점검 (최소 감시)

2GB에서는 감시 스택(VictoriaMetrics 등)을 서버에 올리지 않는다. 대신 다음 두 가지만 쓴다.

1. **외부 가동 확인**: 외부 무료 가동 확인 서비스가 `https://loakraken.gg/about`을 몇 분마다 부르고, 실패하면 Slack #alerts로 알린다(볼트 "감시와 알림 설계" 9절의 가동 확인용 웹훅).
2. **서버 자원 점검** `monitor/host-check.sh`(systemd 타이머, 5분 간격)
   - `host-metrics.log`에 한 줄씩 남긴다: 시각, 메모리 사용률, 스왑 사용량, 디스크 사용률, 컨테이너 재시작 수. 30일치만 둔다. 4GB 증설 판단 자료다.
   - Valkey 축출 수(`evicted_keys` 누적)도 기록한다. 5분 사이 100개 넘게 밀려나면 알린다. 캐시가 계속 밀려나면 공식 API 호출이 늘어나는 신호이므로 `VALKEY_MAXMEMORY`·`VALKEY_MEM_LIMIT`을 올린다.
   - Slack 알림: 메모리 90% 이상이면서 스왑 512MB 이상이 15분(3회) 이어질 때, 디스크 85% 이상, unhealthy 컨테이너, 컨테이너 재시작(메모리 한도 초과 가능성). 같은 문제는 처음 한 번과 복구 때만 알린다.

설치(pull 배포 설치 뒤, 환경마다 1회):

```bash
ENV=production
cd /opt/loakraken/$ENV
sudo cp monitor/loakraken-host-check@.service monitor/loakraken-host-check@.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now loakraken-host-check@$ENV.timer
sudo systemctl start loakraken-host-check@$ENV.service && tail -n 3 host-metrics.log
```

### 스냅숏 정책

- Lightsail **자동 스냅숏은 켜지 않는다**. DB는 R2 암호화 백업([backup.md](backup.md), 매일)과 배포 전 덤프(`backups/pre-deploy-*`)가 기본이다.
- **수동 스냅숏은 1개만** 둔다. 서버 준비를 마친 직후, 그리고 OS 업그레이드·Docker 업그레이드처럼 서버 자체를 바꾸기 전에 새로 만들고 이전 것은 지운다. 비용은 저장된 양 기준 $0.05/GB-월이다(사용량 10GB면 약 $0.5/월, 추정).
- 서버를 통째로 잃으면: 스냅숏으로 새 인스턴스를 만들고 고정 IP를 옮긴 뒤, R2 최신 백업으로 DB를 복구한다(스냅숏 이후 데이터).

### 4GB로 올리는 기준

다음 중 하나가 **7일 동안 반복**되면 PM 총괄을 거쳐 사용자에게 증설($12 → $24/월)을 요청한다.

- `host-metrics.log`에서 메모리 90% 이상 + 스왑 512MB 이상이 하루 여러 번 15분 넘게 이어짐(Slack 메모리 알림이 주 3회 이상)
- 메모리 한도 초과로 컨테이너가 재시작됨(`docker inspect --format '{{.State.OOMKilled}}'` true) 주 1회 이상
- 사이트 응답이 느려지고(외부 가동 확인의 응답 시간 상승) 그 시간에 스왑 사용이 늘어남

올리는 순서(사용자 콘솔 작업 + 운영팀 안내): 수동 스냅숏 → 스냅숏에서 4GB 인스턴스 생성(같은 서울 리전) → 고정 IP를 새 인스턴스로 옮김 → 방화벽 규칙 확인(인스턴스별 설정) → `stack.env` 한도를 4GB 값으로 → `up -d --wait` → 이전 인스턴스 삭제. 고정 IP가 그대로라 DNS는 바꾸지 않는다.

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

1. Ubuntu LTS 등 Linux 서버, Docker Engine + compose 플러그인 설치. 2GB 서버는 먼저 스왑 2GB를 만든다(위 "스왑 2GB").
2. 방화벽(Lightsail): 443은 Cloudflare 대역만, 80은 닫음, 22는 관리자 IP만 + "Allow Lightsail browser SSH/RDP" 선택. 자세한 내용은 [cloudflare.md](cloudflare.md) "Lightsail 방화벽".
3. SSH 설정: 키 인증만 쓴다. `/etc/ssh/sshd_config.d/00-loakraken.conf`에 `PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PermitRootLogin no`를 넣고 `sudo sshd -t && sudo systemctl reload ssh`. sshd는 설정 파일을 이름 순서로 읽고 **처음 나온 값**을 쓰므로 `00-`으로 시작하게 한다(클라우드 이미지의 `60-cloudimg-settings.conf`보다 먼저). 확인: `sudo sshd -T | grep -E '^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin) '`가 모두 `no`. 설정 후 창을 닫기 전에 새 창에서 키 로그인이 되는지 확인한다. 브라우저 SSH는 Lightsail이 관리하는 키로 기본 사용자(ubuntu)에 접속하므로 이 설정과 함께 쓸 수 있다. 서버에 배포용 SSH 키는 두지 않는다(GitHub가 서버에 접속하지 않음).
4. `/opt/loakraken/production/stack.env`를 `deploy/stack.production.env.example`에서, `/opt/loakraken/staging/stack.env`를 `deploy/stack.staging.env.example`에서 만든다. `DEPLOY_CHANNEL`·`DEPLOY_REGISTRY`가 들어 있는지 확인한다.
5. 위 "비밀값" 파일을 환경별로 만든다. Slack 알림을 받으려면 `secrets/slack_webhook_alerts`(웹훅 URL 한 줄, 444)도 만든다.
6. GHCR 읽기: 사용자가 `read:packages` 권한만 있는 토큰으로 서버에서 `sudo docker login ghcr.io`를 한 번 한다. pull 배포는 root로 돌므로 root 계정에 로그인해야 한다.
7. GitHub 설정(사용자)
   - Environments는 만들지 않는다. 조직 free 플랜 + 비공개 저장소라 쓸 수 없다(GitHub 공식 문서, 10-10 확인). 운영 승인은 서버 `approve.sh`가 한다(아래 "운영 승인").
   - 저장소 변수 `PUBLISH_IMAGES=true`(두 저장소). 이전에는 이미지가 GHCR에 올라가지 않는다.
   - `loakraken-web` 패키지 설정 → Manage Actions access에 `loakraken` 저장소를 **Write**로 추가한다. Deploy 워크플로가 web 이미지의 채널 태그를 옮기려면 필요하다.
   - SSH 관련 비밀값(`DEPLOY_HOST` 등)은 만들지 않는다.
8. DNS: Porkbun에서 네임서버를 Cloudflare로 바꾸고, Cloudflare에서 `loakraken.gg` A(그리고 IPv6가 있으면 AAAA) 레코드를 Lightsail 고정 IP로 **프록시 켬(주황 구름)**으로 둔다. SSL 모드는 Full(strict), 원 서버 인증서는 Cloudflare Origin CA(`secrets/tls/`). `stack.env`의 `CADDYFILE=Caddyfile.cloudflare`. 자세한 내용은 [cloudflare.md](cloudflare.md).
9. 첫 배포 승인(아래 "배포" 1~2) 뒤 pull 배포를 설치한다(아래 "pull 배포 설치"). 이어서 자원 점검 타이머를 설치한다(위 "자원 점검").

## 배포 (서버가 가져오는 방식)

GitHub는 서버에 접속하지 않는다. Deploy 워크플로가 GHCR 채널 태그(`:production`·`:staging`)를 옮기고, 서버가 그 태그를 따라간다(PM 총괄 결정 2026-10-09). **운영은 사용자가 서버에서 승인한 릴리스만 배포한다**(PM 총괄 결정 2026-10-10, 아래 "운영 승인").

```
병합 → CI(빌드·테스트) 통과 → publish: sha- 태그로 api·web·배포 번들 업로드(테스트 실패 커밋은 이미지가 없어 배포 불가)
     → Actions › Deploy 실행(환경·태그 입력, 운영은 사용자만) → 채널 태그 이동
     → 서버 타이머(2분): 스테이징은 바로 배포 / 운영은 Slack에 "승인 대기" → 사용자가 서버에서 approve.sh → 배포 → Slack #alerts에 결과
```

1. develop/master에 병합되면 CI가 빌드·테스트하고, **통과한 커밋만** `ci.yml` publish job이 `sha-<커밋7자>` 태그로 api·배포 번들(`loakraken-deploy`)을, web 저장소의 `frontend-ci.yml` publish job이 web 이미지를 올린다. 테스트가 실패한 커밋은 이미지가 없으므로 Deploy에서 태그를 찾지 못해 배포되지 않는다.
   - 같은 브랜치에 연달아 push하면 CI가 앞 실행을 취소한다(concurrency). 그래서 앞 커밋은 이미지 없이 끝날 수 있다. 배포는 최신 커밋 기준이라 의도된 동작이다.
   - 업로드를 CI 워크플로의 후속 job(`needs`)으로 둔 이유: 기본 브랜치가 master라 `workflow_run` 트리거는 master에 반영된 워크플로만 쓴다. develop에서 바로 효과를 내려고 이 방식을 골랐다. master 반영 뒤 분리가 필요하면 다시 검토한다.
2. Actions → Deploy → 환경·`api_tag`·`web_tag`를 넣고 실행한다. production은 `sha-` 태그만 받고 `confirm`에 `production`을 입력해야 한다(실수 방지). **Claude 세션은 production으로 실행하지 않는다**(PM 총괄 결정 10-10, 사용자만). 배포 번들은 `api_tag`와 같은 커밋을 쓴다. 실행 요약에 옮긴 digest가 남는다.
3. 서버의 `loakraken-pull-deploy@<환경>.timer`가 2분 안에 바뀐 태그를 본다. `pull-deploy.sh`가 하는 일:
   1. 채널 태그 3개를 받아 digest를 확인한다. `.deployed`와 같으면 끝, `.failed`(실패했던 릴리스)와 같으면 다시 시도하지 않는다.
   1-1. 운영(`DEPLOY_APPROVAL`이 비었거나 `required`)은 `.approved`(approve.sh가 기록)와 같을 때만 넘어간다. 아니면 `.pending`에 남기고 Slack에 "승인 대기"를 릴리스마다 한 번 보낸 뒤 끝낸다.
   2. 배포 번들을 풀어 compose·Caddyfile·cloudflare·backup·pull 파일을 바꾼다(직전 파일은 `.bundle.prev`에 보관).
   3. DB 백업(`backups/pre-deploy-*.dump`, 최근 14개 유지)
   4. 직전 이미지를 `images.prev.env`로 보관하고 `images.env`에 **digest로 고정**한 이미지를 쓴다.
   5. `up -d --wait`(헬스 체크 통과까지 최대 5분). 성공하면 `.deployed`·`deploy-history.log`에 남기고 Slack에 알린다.
   6. 실패하면 직전 이미지·번들로 되돌리고 `.failed`에 남긴 뒤 Slack에 알린다.
4. 배포 후 15분 관찰: 오류율, `loakraken.lostark.gate{event=upstream_429}`, 응답 지연

배포 전 백업은 같은 서버 디스크에만 있다. 서버를 잃으면 함께 잃으므로 외부 정기 백업은 [backup.md](backup.md)를 따른다(출시 점검표 B6).

### 운영 승인 (approve.sh)

무료 플랜 비공개 저장소라 GitHub Environments 승인자를 쓸 수 없다(PM 총괄 결정 2026-10-10: 서버 승인 + Claude 세션의 운영 Deploy 금지). Claude 세션은 Lightsail 콘솔(브라우저 SSH)에 들어갈 수 없으므로 승인은 사람만 할 수 있다.

서버 안에서(Lightsail 브라우저 SSH, 한 줄):

```bash
sudo /opt/loakraken/production/pull/approve.sh
```

- 새 릴리스와 지금 배포된 릴리스의 커밋(7자)·빌드 시각·GitHub 비교 링크(api·web)를 보여 주고 `Approve this release for production? [y/N]`를 묻는다. 브라우저 SSH에서 한글이 깨지므로 출력은 영문이다.
- `y`: `.approved`에 기록하고 바로 배포를 실행한 뒤 `DEPLOY_OK` 또는 `DEPLOY_NOT_DONE`(journalctl 안내)을 보여 준다. 실패했던 릴리스를 승인하면 다시 시도한다. 승인 기록은 `deploy-history.log`에도 남는다.
- 그 밖의 입력: `NOT_APPROVED`, 아무것도 바뀌지 않는다.
- 스테이징은 `DEPLOY_APPROVAL=auto`라 승인 없이 따라간다.
- 로컬 시험(10-10, 로컬 레지스트리): 승인 전 2회 실행은 승인 대기 알림 1회 뒤 대기만 기록, `n`이면 `.approved` 없음, `y`면 `.approved`가 대기 릴리스와 같아짐, 배포된 릴리스가 있으면 비교 링크(compare)를 보여 줌.

### pull 배포 설치 (환경마다 1회)

첫 배포 승인으로 채널 태그가 생긴 뒤 서버에서 사용자가 실행한다. 이후 `pull/` 파일은 배포 번들로 자동 갱신된다.

- systemd 유닛 파일(`pull`·`monitor`·`backup`의 `.service`·`.timer`)이 번들에서 바뀌면, **운영 배포가 성공한 뒤 `pull-deploy.sh`가 서버에 이미 설치된 유닛만 바꿔 넣고 `daemon-reload`** 한다(Slack "systemd 유닛 갱신"). 새 유닛을 설치하거나 타이머를 켜지는 않는다(그건 아래처럼 사람이 한 번). 유닛 이름을 운영·스테이징이 같이 쓰므로 스테이징(develop 번들)은 유닛을 바꾸지 않는다(#280).
- `approve.sh`도 시작 전에 pull 서비스·타이머가 `daemon-reload`를 기다리는 상태면 스스로 다시 읽는다. v0.2.0 배포(10-11) 때 사용자가 경고를 보고 따로 `daemon-reload`를 했던 것을 없앤다.

```bash
ENV=production
cd /opt/loakraken/$ENV
cid=$(sudo docker create ghcr.io/loakraken/loakraken-deploy:$ENV true)
sudo docker cp "$cid:/bundle/pull" . && sudo docker rm "$cid"
sudo cp pull/loakraken-pull-deploy@.service pull/loakraken-pull-deploy@.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now loakraken-pull-deploy@$ENV.timer
sudo /opt/loakraken/$ENV/pull/approve.sh                     # 운영: 첫 릴리스를 보고 y로 승인하면 바로 배포
```

스테이징은 승인 없이 타이머가 배포한다(`sudo systemctl start loakraken-pull-deploy.service`로 바로 실행 가능).

## 롤백

평소 롤백도 **승인 절차를 거친다**. 사용자가 Actions → Deploy를 이전 `sha-` 태그로 다시 실행하고(`deploy-history.log`·실행 요약에서 직전 태그를 찾는다), 서버에서 `approve.sh`로 승인하면 그 릴리스로 돌아간다.

- 배포가 헬스 체크에서 실패하면 서버가 스스로 직전 릴리스로 되돌린다(위 3-6). 사람이 할 일은 원인 확인과 고친 릴리스 승인이다.
- **실패한 릴리스 다시 시도**: 실패가 일시적이었거나(배포 순간 DB·Valkey 장애로 readiness 503) 비밀 파일을 고친 경우다. 운영은 `sudo /opt/loakraken/production/pull/approve.sh`로 같은 릴리스를 다시 승인하면 `.failed`를 지우고 바로 다시 시도한다(경고 문구가 나온다).

- 레지스트리(GHCR) 장애로 이미지를 못 받으면 같은 오류는 처음 한 번만 Slack에 알리고, 복구되면 "복구됨"을 알린다(2분마다 반복하지 않음).
- 긴급 수동 롤백(GitHub 장애 등, 관리자 SSH 또는 Lightsail 브라우저 SSH):

```bash
cd /opt/loakraken/production
sudo systemctl stop loakraken-pull-deploy@production.timer
sudo cp images.prev.env images.env
sudo docker compose -f compose.prod.yaml --env-file stack.env --env-file images.env up -d --wait
# 원인을 고친 릴리스를 Deploy로 승인한 뒤 타이머를 다시 켠다
sudo systemctl start loakraken-pull-deploy@production.timer
```

### 스키마와 되돌리기 (expand-contract)

되돌리기는 이미지·번들만 되돌리고 **DB 스키마(Flyway)는 되돌리지 않는다**. 새 릴리스가 마이그레이션을 적용한 뒤 헬스 체크에서 실패하면, 이전 앱이 새 스키마 위에서 뜬다.

- 스키마를 바꾸는 PR은 **expand-contract**로 나눈다. 이번 릴리스는 추가만 한다(열·테이블·인덱스 추가, 새 열은 NULL 허용 또는 기본값). 이전 코드가 더 이상 쓰지 않게 된 **다음 릴리스**에서 이름 변경·삭제·NOT NULL 강화를 한다.
- 추가만 한 경우 이전 앱은 새 스키마에서도 뜬다(Flyway는 기본으로 모르는 이후 버전을 무시하고, Hibernate validate는 남는 열을 문제 삼지 않는다).
- 이름 변경·삭제가 섞인 배포가 실패하면 되돌리기도 실패한다(Slack "되돌리기도 실패"). 그때는 아래 "배포 전 덤프로 복구"를 한다.

### 배포 전 덤프로 복구

되돌리기도 실패했거나 마이그레이션이 데이터를 망가뜨렸을 때 쓴다. 덤프 이후의 쓰기(그 사이 수집된 관측값 등)는 잃는다. 관리자 SSH 또는 Lightsail 브라우저 SSH로 실행한다.

```bash
cd /opt/loakraken/production
sudo systemctl stop loakraken-pull-deploy@production.timer
C="sudo docker compose -f compose.prod.yaml --env-file stack.env --env-file images.env"
ls -t backups/pre-deploy-*.dump | head -3          # 실패한 배포 직전 덤프를 고른다(파일 이름 = UTC 시각)
DUMP=backups/pre-deploy-<시각>.dump
$C stop api                                          # 쓰기를 멈춘다(사이트는 점검 중 상태)
# 덤프 이후 생긴 표까지 지우려고 public 스키마를 다시 만든 뒤 한 트랜잭션으로 복원한다
$C exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -qc "DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION $POSTGRES_USER;" && pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error --single-transaction' < "$DUMP"
sudo cp images.prev.env images.env                   # 덤프와 맞는 이전 이미지(자동 되돌리기가 이미 했으면 같음)
$C up -d --wait
```

- 복구한 뒤 서비스를 다시 열기 전에 [backup.md](backup.md) "복구 시 삭제 요청 다시 적용"을 한다.
- 원인을 고친 릴리스를 Deploy로 승인한 뒤 타이머를 다시 켠다(`sudo systemctl start loakraken-pull-deploy@production.timer`).
- 로컬 확인(postgres 18.6): 덤프 → 열 이름 변경·표 추가·행 추가 → 위 명령으로 복원하면 원래 열·행만 남고 추가된 표는 사라졌다.

## 저장소 공개와 GitHub 보안 설정 (10-10)

두 저장소는 2026-10-10부터 공개다(사용자 결정, "배포 전까지 공개"). 공개 저장소에서는 Actions 시간 제한이 없고 룰셋·비밀값 스캔·푸시 보호를 무료로 쓴다. 설정 값과 이유는 볼트 "공개 저장소 보안 설정"에 있다(사용자가 설정).

- Actions: 외부 기여자 포크 PR은 승인 필요, 기본 토큰 읽기 전용, 워크플로가 PR을 만들거나 승인하지 못함
- 비밀값 스캔·푸시 보호·Dependabot 알림 켬
- 룰셋: master·develop 삭제·강제 push 금지, PR 필수(승인 수 0, 검토는 담당 PM 판정), 필수 상태 검사 `build`(백엔드)·`Lint and production build`(웹), 선형 이력 요구는 끔(릴리스는 merge commit)
- 운영 승인은 그대로 서버 `approve.sh`(Environments는 세션과 사람을 구분하지 못해 쓰지 않음)
- **다시 비공개로 돌리면** 무료 플랜에서는 룰셋·비밀값 스캔·푸시 보호가 꺼지고 Actions 월 2,000분 제한이 다시 걸린다. 그때는 로컬 pre-push 훅(`scripts/git-hooks`)과 서버 승인만 남으므로, 전환 전에 PM 총괄과 CI 시간 대책(지출 한도·자체 러너)을 다시 정한다.

## 번들과 서버 권한

`pull-deploy.sh`는 root로 돌고, 배포 번들 안의 스크립트(`pull/`·`backup/`)와 compose 파일을 그대로 실행한다. 그래서 **서버 root 권한을 지키는 선은 코드 리뷰와 운영 승인**이다.

- 번들은 `ci.yml` publish job이 develop·master에 병합되고 **빌드·테스트를 통과한** 커밋으로만 만든다(PR에서는 빌드 확인만, 업로드하지 않음).
- 운영 채널은 Deploy 작업이 `sha-` 태그 번들만 옮기고, 서버는 사용자가 approve.sh로 승인한 digest만 고정해 실행한다.
- 그러므로 `deploy/**`를 바꾸는 PR은 PM-백엔드 검토를 거친다(셸 스크립트·compose 변경은 서버 root 권한 변경과 같다고 보고 본다).
- GHCR 쓰기 권한(packages: write)은 Actions 작업에만 있다. 사람의 개인 토큰에 쓰기 권한을 주지 않는다. 서버의 GHCR 토큰은 읽기 전용이다.

## 공식 API 호출 한도와 배포

- 운영 키 1개 = 90회/분(공식 100, 여유 10). 운영 스택의 모든 백엔드가 나눠 쓴다.
- 현재 `up -d`는 api 컨테이너를 교체하므로 구·신이 겹치지 않는다(수십 초 중단). 겹쳐 띄우는 무중단 배포는 공유 호출 게이트(#107)가 들어온 뒤에만 한다.
- 수집기·예열은 운영 stack.env에서만 true다.
- 수집기 속도 `STATS_COLLECTOR_INTERVAL`(기본 3s)·`STATS_COLLECTOR_RESERVE`(기본 40, 사용자 요청에 남겨 두는 분당 몫). 공개 전 사용자 트래픽이 없을 때만 한시로 1s·25를 허용한다(수집기 약 60/분 + 예열 15 = 75 < 게이트 90, PM-백엔드 10-10). **도메인 공개 전에 3s·40으로 되돌린다.**

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
