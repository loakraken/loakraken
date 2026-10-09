# Cloudflare 프록시 운영

사용자 결정(2026-10-09): 운영은 **AWS Lightsail 서울 4GB + 앞단 Cloudflare 무료 프록시 + DB 외부 백업 Cloudflare R2**. 계정 생성·결제·DNS 변경·비밀값 입력은 사용자가 직접 한다. 배포·운영은 설정 파일·절차·확인을 맡는다.

## 요청 흐름

```
사용자 ─HTTPS→ Cloudflare(프록시) ─HTTPS(Full strict)→ Lightsail 443(Cloudflare 대역만 허용)
        → edge(Caddy, Caddyfile.cloudflare) → web(Next) → api(Spring) → postgres·valkey
```

## 실제 클라이언트 IP

- Cloudflare는 실제 IP를 `CF-Connecting-IP`로 보낸다. `X-Forwarded-For`는 덧붙이는 방식이라 원 서버에서는 그대로 믿지 않는다. 무료 플랜에는 `True-Client-IP`가 없다(Cloudflare HTTP 헤더 문서).
- `deploy/Caddyfile.cloudflare`
  - `servers.trusted_proxies`: `deploy/cloudflare/trusted-proxies.caddy`(Cloudflare 공식 대역)에서 온 요청만 믿는다.
  - `client_ip_headers CF-Connecting-IP`: 믿는 요청일 때만 이 헤더를 실제 IP로 쓴다.
  - web으로는 `X-Forwarded-For`를 실제 IP **하나로 덮어써** 넘기고, `CF-Connecting-IP`는 지운다. Next(web #78)가 이 값을 그대로 백엔드로 넘기고, 백엔드는 web 고정 주소에서 온 헤더만 믿는다(#131).
- 로컬 검증(2026-10-09, #134)
  - 신뢰 대역에서 `CF-Connecting-IP: 203.0.113.9`와 위조 XFF `6.6.6.6`을 보내면, web은 `203.0.113.9` 하나만 받는다.
  - 대역 밖에서 같은 헤더를 보내면, web은 실제 접속 주소만 받는다. `CF-Connecting-IP`도 지워진다.
  - IPv6 클라이언트(`2001:db8::1234`)는 그대로 전달된다. #108의 IPv6 /64 정규화가 이 값을 기준으로 한다.

### Cloudflare 대역 갱신

```bash
deploy/cloudflare/update-ips.sh            # 공식 목록과 비교. 바뀌었으면 종료 코드 1
deploy/cloudflare/update-ips.sh --write    # 대역 파일 갱신 → PR로 반영
```

- 월 1회 확인한다(감시 설계의 "확인" 등급 점검에 넣는다). 바뀌면 **대역 파일과 Lightsail 방화벽을 함께** 바꾼다.
- 2026-10-09 기준 IPv4 15개, IPv6 7개다(https://www.cloudflare.com/ips/).

## HTTPS: SSL 모드 Full(strict) + Origin CA 인증서

- Full(strict)는 원 서버 인증서가 **공인 CA 또는 Cloudflare Origin CA**에서 발급되고, 만료 전이며, 호스트 이름이 맞아야 한다(Cloudflare 문서).
- Caddy의 자동 발급(ACME)은 Cloudflare 프록시 뒤에서 확인 과정이 막힐 수 있어 쓰지 않는다. **Origin CA 인증서**를 쓴다.
- 인증서·키는 비밀값이다. 서버의 `/opt/loakraken/production/secrets/tls/origin.pem`·`origin.key`에만 둔다(디렉터리 700, 파일 400, root). compose가 edge에 읽기 전용으로 마운트한다.

## Lightsail 방화벽

| 규칙 | 출발지 | 이유 |
|---|---|---|
| HTTPS TCP 443 (IPv4) | Cloudflare IPv4 15개 | 원 서버를 Cloudflare만 부를 수 있게 |
| HTTPS TCP 443 (IPv6) | Cloudflare IPv6 7개 | IPv6 방화벽은 따로다 |
| HTTP 80 | **열지 않는다**(기본 규칙 삭제) | Cloudflare가 HTTP→HTTPS를 처리하고 원 서버로는 443만 쓴다 |
| SSH 22 | 아래 "SSH" | |

- Lightsail 콘솔은 규칙 하나에 출발지 30개까지 넣을 수 있고(API는 60개), 방화벽마다 규칙은 IPv4·IPv6 각 60개까지다. 출발지 목록은 `update-ips.sh`가 쉼표로 출력한다.
- **SSH**: 배포 워크플로는 GitHub 러너에서 SSH로 접속한다. 러너 IP가 넓고 자주 바뀌어 출발지를 좁히기 어렵다. 그래서 다음 중 하나를 고른다(서버 준비 때 PM 총괄과 결정).
  1. 22를 열되 키 인증만 허용(비밀번호 로그인·root 로그인 끔), fail2ban
  2. 22는 관리자 IP만 열고, 배포는 서버가 스스로 가져오는 방식으로 바꾼다(후속 작업)

## Cloudflare 캐시 주의

- HTML·`/api/*`(JSON)는 기본으로 캐시되지 않는다. **"Cache Everything" 규칙을 만들지 않는다**(캐릭터·시세가 사용자 사이에 섞일 수 있음).
- `/_next/static/*`는 파일 이름에 해시가 있어 캐시돼도 안전하다.

## R2 외부 백업

- `deploy/backup/rclone.conf.example`의 R2 예시를 쓴다(provider Cloudflare, endpoint `https://<계정 ID>.r2.cloudflarestorage.com`, region auto).
- 토큰은 그 버킷 하나에 대한 "Object Read & Write"만 준다. 값은 서버 `secrets/rclone.conf`에만 둔다.
- 저장 위치: `apac` 위치 힌트를 줄 수 있지만 **보장이 아니다**. 보장되는 관할은 eu·us·fedramp뿐이다(R2 data location 문서). 개인정보 처리방침에는 "국외(위치 보장 없음)"로 적는다.
- 요금(공식, 2026-10-09): Standard 저장 월 10GB·Class A 100만·Class B 1,000만 회 무료, 초과 시 $0.015/GB-월·$4.50/백만·$0.36/백만, 이그레스 무료. 하루 1회 백업·30일 보관이면 무료 구간 안이다(추정).

## 출처 (확인일 2026-10-09)

- [Cloudflare IP 대역](https://www.cloudflare.com/ips/)
- [Cloudflare HTTP 헤더](https://developers.cloudflare.com/fundamentals/reference/http-headers/)
- [SSL Full(strict)](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/)
- [R2 요금](https://developers.cloudflare.com/r2/pricing/), [R2 데이터 위치](https://developers.cloudflare.com/r2/reference/data-location/)
- [Lightsail 방화벽](https://docs.aws.amazon.com/lightsail/latest/userguide/understanding-firewall-and-port-mappings-in-amazon-lightsail.html)
- [Caddy 전역 옵션(trusted_proxies·client_ip_headers)](https://caddyserver.com/docs/caddyfile/options)
