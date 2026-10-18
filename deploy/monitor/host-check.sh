#!/usr/bin/env bash
# 서버 자원 점검(2GB 서버용 최소 감시). systemd 타이머(loakraken-host-check@<환경>.timer)가 5분마다 실행한다.
#
# - 기록: host-metrics.log 한 줄(시각 메모리% 스왑MB 디스크% 재시작수 Valkey축출수) → 4GB 증설 판단 자료(운영 문서 deploy "4GB로 올리는 기준")
# - 알림(Slack, 같은 문제는 처음 한 번과 복구 때만):
#   메모리 90% 이상이면서 스왑 512MB 이상이 3회 연속 / 디스크 85% 이상 / 컨테이너 unhealthy / 컨테이너 재시작 수 증가 / Valkey 축출 급증
# 알림 문구에는 개인정보를 넣지 않는다. 스택 디렉터리(/opt/loakraken/<환경>)에서 root로 실행한다.
set -euo pipefail

STACK_DIR="${STACK_DIR:-$(pwd)}"
cd "$STACK_DIR"
[[ -f stack.env ]] || { echo "stack.env가 없습니다($STACK_DIR)" >&2; exit 1; }
env_value() { sed -n "s/^$1=//p" stack.env | tail -n 1; }
SECRETS_DIR="$(env_value SECRETS_DIR)"
STACK_NAME="$(env_value STACK_NAME)"
STACK_NAME="${STACK_NAME:-loakraken}"

MEM_ALERT_PCT="${MEM_ALERT_PCT:-90}"
SWAP_ALERT_MB="${SWAP_ALERT_MB:-512}"
DISK_ALERT_PCT="${DISK_ALERT_PCT:-85}"
KEEP_LINES="${KEEP_LINES:-8640}"   # 5분 간격 30일

notify() {
  local hook="${SECRETS_DIR}/slack_webhook_alerts" text="[${STACK_NAME}/자원] $1"
  if [[ -n "$SECRETS_DIR" && -r "$hook" ]]; then
    curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
      --data "$(printf '{"text":"%s"}' "${text//\"/\'}")" "$(cat "$hook")" >/dev/null || echo "Slack 알림 실패" >&2
  fi
  echo "$1"
}

# 같은 문제는 처음 한 번만 알리고, 사라지면 복구를 알린다. 상태는 .host-alerts/<키> 파일.
mkdir -p .host-alerts
raise() { [[ -f ".host-alerts/$1" ]] || { : > ".host-alerts/$1"; notify "$2"; }; }
clear_alert() { [[ -f ".host-alerts/$1" ]] && { rm -f ".host-alerts/$1"; notify "$2"; }; return 0; }

mem_total=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
mem_avail=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
swap_total=$(awk '/^SwapTotal:/{print $2}' /proc/meminfo)
swap_free=$(awk '/^SwapFree:/{print $2}' /proc/meminfo)
mem_pct=$(( (mem_total - mem_avail) * 100 / mem_total ))
swap_mb=$(( (swap_total - swap_free) / 1024 ))
disk_pct=$(df -P / | awk 'NR==2{gsub("%","",$5); print $5}')

restarts=0
unhealthy=()
declare -A svc_restarts=()
while read -r name health count; do
  [[ -n "$name" ]] || continue
  restarts=$(( restarts + count ))
  svc_restarts["$name"]=$(( ${svc_restarts["$name"]:-0} + count ))
  [[ "$health" == "unhealthy" ]] && unhealthy+=("$name")
done < <(docker ps -aq --filter "label=com.docker.compose.project=${STACK_NAME}" \
  | xargs -r docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}')

# Valkey 축출 수(누적). 캐시가 계속 밀려나면 공식 API 호출이 늘어나는 신호다. 암호는 컨테이너 안 비밀 파일에서 읽는다.
valkey_id="$(docker ps -q --filter "label=com.docker.compose.project=${STACK_NAME}" --filter "label=com.docker.compose.service=valkey" | head -n 1)"
evicted=-1
if [[ -n "$valkey_id" ]]; then
  # shellcheck disable=SC2016  # 변수는 valkey 컨테이너 안에서 펼친다.
  evicted="$(docker exec "$valkey_id" sh -c 'VALKEYCLI_AUTH="$(cat /run/secrets/valkey_password)" valkey-cli info stats' 2>/dev/null \
    | tr -d '\r' | sed -n 's/^evicted_keys://p')"
  evicted="${evicted:--1}"
fi

printf '%s mem=%s%% swap=%sMB disk=%s%% restarts=%s evicted=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$mem_pct" "$swap_mb" "$disk_pct" "$restarts" "$evicted" >> host-metrics.log
tail -n "$KEEP_LINES" host-metrics.log > host-metrics.log.tmp && mv host-metrics.log.tmp host-metrics.log

# 모니터링 대시보드용: 서비스별 컨테이너 재시작 수(누적)를 node-exporter 텍스트 파일로 남긴다(라벨은 compose 서비스 이름뿐).
# 알림은 아래에서 이 스크립트가 보내고, Grafana는 그래프만 그린다. 모니터링을 켜지 않은 서버에서도 써 두기만 한다(작은 파일).
TEXTFILE_DIR="${TEXTFILE_DIR:-/var/lib/node_exporter/textfile}"
if mkdir -p "$TEXTFILE_DIR" 2>/dev/null; then
  {
    echo "# HELP loakraken_container_restarts 컨테이너 재시작 수(누적, 서버 점검 스크립트)"
    echo "# TYPE loakraken_container_restarts gauge"
    for svc in "${!svc_restarts[@]}"; do
      printf 'loakraken_container_restarts{service="%s"} %s\n' "$svc" "${svc_restarts[$svc]}"
    done | sort
    # node-exporter는 호스트 / 를 붙이지 않으므로(파일 시스템 수집 끔) 루트 디스크 크기·여유를 여기서 넘긴다.
    df -PB1 / | awk 'NR==2{
      print "# HELP loakraken_root_disk_size_bytes 루트(/) 파일 시스템 크기(서버 점검 스크립트)"
      print "# TYPE loakraken_root_disk_size_bytes gauge"
      print "loakraken_root_disk_size_bytes " $2
      print "# HELP loakraken_root_disk_avail_bytes 루트(/) 파일 시스템 남은 공간(서버 점검 스크립트)"
      print "# TYPE loakraken_root_disk_avail_bytes gauge"
      print "loakraken_root_disk_avail_bytes " $4 }'
  } > "$TEXTFILE_DIR/loakraken.prom.tmp" && mv "$TEXTFILE_DIR/loakraken.prom.tmp" "$TEXTFILE_DIR/loakraken.prom"
fi

# 메모리: 순간 치솟음은 무시하고 3회(15분) 연속일 때만 알린다.
if (( mem_pct >= MEM_ALERT_PCT && swap_mb >= SWAP_ALERT_MB )); then
  streak=$(( $(cat .host-alerts/mem.streak 2>/dev/null || echo 0) + 1 ))
  echo "$streak" > .host-alerts/mem.streak
  (( streak >= 3 )) && raise mem "메모리 ${mem_pct}%, 스왑 ${swap_mb}MB가 15분 넘게 이어짐. 증설 기준 확인(운영 문서 deploy)"
else
  rm -f .host-alerts/mem.streak
  clear_alert mem "메모리 정상으로 돌아옴(${mem_pct}%, 스왑 ${swap_mb}MB)"
fi

if (( disk_pct >= DISK_ALERT_PCT )); then
  raise disk "디스크 ${disk_pct}% 사용. 오래된 이미지·덤프 정리 필요(docker image prune, backups/)"
else
  clear_alert disk "디스크 정상으로 돌아옴(${disk_pct}%)"
fi

if (( ${#unhealthy[@]} > 0 )); then
  raise unhealthy "unhealthy 컨테이너: ${unhealthy[*]}"
else
  clear_alert unhealthy "모든 컨테이너 정상"
fi

last_restarts=$(cat .host-alerts/restarts.count 2>/dev/null || echo "$restarts")
if (( restarts > last_restarts )); then
  notify "컨테이너 재시작 $(( restarts - last_restarts ))회 발생(메모리 한도 초과 가능성). docker compose ps·docker inspect --format '{{.State.OOMKilled}}'로 확인"
fi
echo "$restarts" > .host-alerts/restarts.count

# 5분 사이 축출이 EVICT_ALERT개를 넘으면 알린다(Valkey가 다시 시작돼 누적이 줄면 기준만 새로 잡는다).
EVICT_ALERT="${EVICT_ALERT:-100}"
if (( evicted >= 0 )); then
  last_evicted=$(cat .host-alerts/evicted.count 2>/dev/null || echo "$evicted")
  if (( evicted - last_evicted > EVICT_ALERT )); then
    raise evict "Valkey 축출 5분에 $(( evicted - last_evicted ))개. 캐시가 밀려나 공식 호출이 늘 수 있음(VALKEY_MAXMEMORY 검토)"
  elif (( evicted == last_evicted )); then
    clear_alert evict "Valkey 축출 멈춤"
  fi
  echo "$evicted" > .host-alerts/evicted.count
fi
