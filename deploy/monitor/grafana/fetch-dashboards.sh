#!/usr/bin/env bash
# 공개 대시보드(grafana.com) 2개를 고정 리비전으로 받아 해시를 확인하고 서버 스택 디렉터리에 둔다.
# grafana.com 대시보드는 라이선스 표기가 없어 공개 저장소에 JSON을 넣지 않는다(설계 결정). 서버에서 한 번 실행한다.
#
#   sudo bash /opt/loakraken/production/monitor/grafana/fetch-dashboards.sh /opt/loakraken/production
#
# 결과: <스택 디렉터리>/grafana-dashboards/*.json (compose가 grafana에 읽기 전용으로 붙인다 → 폴더 external).
# 데이터소스 자리표시(${DS_PROMETHEUS})는 프로비저닝 데이터소스 uid로 바꾼다. 해시가 다르면 아무것도 쓰지 않고 멈춘다.
set -euo pipefail

STACK_DIR="${1:-$(pwd)}"
OUT="${STACK_DIR}/grafana-dashboards"
DS_UID="loakraken-prometheus"

# id  리비전  sha256(받은 원본)  파일 이름
DASHBOARDS=(
  "11378 2 4785afd9809accd08d649452f03c6ba17e779cfb0a51286363b6d2c2ffb643f3 spring-boot-system-monitor.json"
  "4701 10 c8ed623c921cda086a8446c06f333e8ec19072a4f209a5b6a06dcb186140ae73 jvm-micrometer.json"
)

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for entry in "${DASHBOARDS[@]}"; do
  read -r id rev sum name <<<"$entry"
  curl -fsS -m 60 -o "$tmp/$name.raw" "https://grafana.com/api/dashboards/${id}/revisions/${rev}/download"
  got="$(sha256sum "$tmp/$name.raw" | cut -d' ' -f1)"
  if [[ "$got" != "$sum" ]]; then
    echo "해시가 다릅니다: ${id} r${rev} (받은 값 ${got:0:16}…). 아무것도 바꾸지 않습니다." >&2
    exit 1
  fi
  sed "s/\${DS_PROMETHEUS}/${DS_UID}/g" "$tmp/$name.raw" > "$tmp/$name"
  # shellcheck disable=SC2016  # 글자 그대로 '${DS_'를 찾는다(펼치지 않음).
  grep -q '\${DS_' "$tmp/$name" && { echo "바꾸지 못한 데이터소스 자리표시가 있습니다: $name" >&2; exit 1; }
done

mkdir -p "$OUT"
for entry in "${DASHBOARDS[@]}"; do
  read -r _ _ _ name <<<"$entry"
  install -m 0644 "$tmp/$name" "$OUT/$name"
done
echo "DASHBOARDS_OK ${#DASHBOARDS[@]}개 → $OUT"
