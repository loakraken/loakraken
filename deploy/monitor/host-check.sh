#!/usr/bin/env bash
# 서버 자원 점검(2GB 서버용 최소 감시). systemd 타이머(loakraken-host-check@<환경>.timer)가 5분마다 실행한다.
#
# - 기록: host-metrics.log 한 줄(시각 메모리% 스왑MB 디스크% 재시작수) → 4GB 증설 판단 자료(docs/ops/deploy.md "4GB로 올리는 기준")
# - 알림(Slack, 같은 문제는 처음 한 번과 복구 때만):
#   메모리 90% 이상이면서 스왑 512MB 이상이 3회 연속 / 디스크 85% 이상 / 컨테이너 unhealthy / 컨테이너 재시작 수 증가
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
while read -r name health count; do
  [[ -n "$name" ]] || continue
  restarts=$(( restarts + count ))
  [[ "$health" == "unhealthy" ]] && unhealthy+=("$name")
done < <(docker ps -aq --filter "label=com.docker.compose.project=${STACK_NAME}" \
  | xargs -r docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}')

printf '%s mem=%s%% swap=%sMB disk=%s%% restarts=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$mem_pct" "$swap_mb" "$disk_pct" "$restarts" >> host-metrics.log
tail -n "$KEEP_LINES" host-metrics.log > host-metrics.log.tmp && mv host-metrics.log.tmp host-metrics.log

# 메모리: 순간 치솟음은 무시하고 3회(15분) 연속일 때만 알린다.
if (( mem_pct >= MEM_ALERT_PCT && swap_mb >= SWAP_ALERT_MB )); then
  streak=$(( $(cat .host-alerts/mem.streak 2>/dev/null || echo 0) + 1 ))
  echo "$streak" > .host-alerts/mem.streak
  (( streak >= 3 )) && raise mem "메모리 ${mem_pct}%, 스왑 ${swap_mb}MB가 15분 넘게 이어짐. 증설 기준 확인(docs/ops/deploy.md)"
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
