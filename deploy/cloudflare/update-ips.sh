#!/usr/bin/env bash
# Cloudflare IP 대역 파일(trusted-proxies.caddy)을 공식 목록과 비교한다.
#   deploy/cloudflare/update-ips.sh          # 비교만. 바뀌었으면 종료 코드 1(감시·월 1회 점검용)
#   deploy/cloudflare/update-ips.sh --write  # 파일을 새 목록으로 바꾼다(PR로 반영)
#
# 대역이 바뀌면 함께 바꿀 것: Lightsail 방화벽 443 허용 출발지(IPv4·IPv6). 아래 출력의 CIDR 목록을 쓴다.
# 공식 목록: https://www.cloudflare.com/ips/
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
target="$here/trusted-proxies.caddy"

v4="$(curl -fsS --max-time 20 https://www.cloudflare.com/ips-v4/)"
v6="$(curl -fsS --max-time 20 https://www.cloudflare.com/ips-v6/)"
ranges="$(printf '%s\n%s\n' "$v4" "$v6" | tr -d '\r' | sed '/^[[:space:]]*$/d')"

# 최소한의 형식 확인(빈 응답·오류 페이지로 파일을 덮어쓰지 않게).
count="$(printf '%s\n' "$ranges" | grep -cE '^[0-9a-f:.]+/[0-9]+$' || true)"
[[ "$count" -ge 10 ]] || { echo "Cloudflare 대역을 제대로 받지 못했습니다(유효 CIDR ${count}개)" >&2; exit 2; }

mapfile -t cidrs <<<"$ranges"
new="$(
  echo "# Cloudflare IP 대역(공식 https://www.cloudflare.com/ips/). update-ips.sh가 만든다. 직접 고치지 않는다."
  printf 'trusted_proxies static'
  printf ' %s' "${cidrs[@]}"
  echo
)"

if [[ "${1:-}" == "--write" ]]; then
  printf '%s\n' "$new" > "$target"
  echo "갱신했습니다: $target (${count}개 대역)"
elif [[ -f "$target" ]] && diff -q <(printf '%s\n' "$new") "$target" >/dev/null; then
  echo "변경 없음(${count}개 대역)"
  exit 0
else
  echo "Cloudflare 대역이 바뀌었습니다. --write로 갱신하고 Lightsail 방화벽도 바꾸세요." >&2
  diff <(cat "$target" 2>/dev/null || true) <(printf '%s\n' "$new") >&2 || true
  exit 1
fi

echo "--- Lightsail 방화벽 HTTPS(443) 허용 출발지"
echo "IPv4: $(printf '%s\n' "$v4" | tr -d '\r' | sed '/^$/d' | paste -sd, -)"
echo "IPv6: $(printf '%s\n' "$v6" | tr -d '\r' | sed '/^$/d' | paste -sd, -)"
