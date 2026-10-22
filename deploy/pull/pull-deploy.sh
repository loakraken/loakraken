#!/usr/bin/env bash
# 서버가 가져오는 배포(pull). systemd 타이머(loakraken-pull-deploy@<환경>.timer)가 몇 분마다 실행한다.
#
# Deploy 워크플로(deploy.yml)가 GHCR 채널 태그(:production/:staging)를 옮기고, 이 스크립트가 그 태그를 따라간다:
#   채널 태그 확인 → (운영) 서버에서 사용자가 approve.sh로 승인한 릴리스인지 확인 → 배포 번들 풀기 → DB 백업
#   → 이미지를 digest로 고정 → up -d --wait
#   → (모니터링 프로필을 켠 서버) prometheus·node-exporter·grafana를 새 설정으로 다시 만든다(실패해도 서비스 배포는 그대로)
#   → 실패하면 직전 이미지·번들로 되돌리고 그 릴리스는 다시 시도하지 않는다 → Slack 알림
#
# 스택 디렉터리(/opt/loakraken/<환경>)에서 root로 실행한다(docker 그룹 권한과 같음, 비밀 디렉터리의 웹훅 파일을 읽기 위해).
set -euo pipefail

# 배포 중에 pull/ 디렉터리(이 스크립트 자신)도 새 번들로 바뀐다. 실행 중인 파일을 건드리지 않게 임시 복사본으로 다시 실행한다.
if [[ -z "${PULL_DEPLOY_COPY:-}" ]]; then
  copy="$(mktemp "${TMPDIR:-/tmp}/pull-deploy.XXXXXX")"
  cp "$0" "$copy"
  STACK_DIR="${STACK_DIR:-$(pwd)}" PULL_DEPLOY_COPY="$copy" exec bash "$copy" "$@"
fi
trap 'rm -f "$PULL_DEPLOY_COPY"' EXIT

STACK_DIR="${STACK_DIR:-$(pwd)}"
cd "$STACK_DIR"

log() { printf '%s pull-deploy: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

[[ -f stack.env ]] || { log "stack.env가 없습니다($STACK_DIR)"; exit 1; }
env_value() { sed -n "s/^$1=//p" stack.env | tail -n 1; }
CHANNEL="$(env_value DEPLOY_CHANNEL)"
REGISTRY="$(env_value DEPLOY_REGISTRY)"
SECRETS_DIR="$(env_value SECRETS_DIR)"
STACK_NAME="$(env_value STACK_NAME)"
[[ "$CHANNEL" =~ ^(production|staging)$ ]] || { log "DEPLOY_CHANNEL은 production 또는 staging이어야 합니다"; exit 1; }
REGISTRY="${REGISTRY:-ghcr.io/loakraken}"

# 같은 스택에서 두 번 겹쳐 돌지 않게 한다.
if command -v flock >/dev/null 2>&1; then
  exec 9>.pull-deploy.lock
  flock -n 9 || { log "이미 실행 중"; exit 0; }
fi

notify() {
  local text="[${STACK_NAME:-loakraken}/${CHANNEL}] $1"
  local hook="${SECRETS_DIR}/slack_webhook_alerts"
  if [[ -n "$SECRETS_DIR" && -r "$hook" ]]; then
    # 알림 문구에는 개인정보(캐릭터명·IP)를 넣지 않는다.
    curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
      --data "$(printf '{"text":"%s"}' "${text//\"/\'}")" "$(cat "$hook")" >/dev/null || log "Slack 알림 실패"
  fi
  log "$1"
}

api_ref="$REGISTRY/loakraken-api:$CHANNEL"
web_ref="$REGISTRY/loakraken-web:$CHANNEL"
bundle_ref="$REGISTRY/loakraken-deploy:$CHANNEL"

# 레지스트리 장애 때 2분마다 같은 알림이 반복되지 않게, 같은 오류는 처음 한 번만 알리고 복구되면 알린다.
pull_error() {
  local msg="이미지를 가져오지 못했습니다: $1"
  if [[ "$msg" != "$(cat .pull-error 2>/dev/null || true)" ]]; then
    printf '%s' "$msg" > .pull-error
    notify "$msg (복구될 때까지 다시 알리지 않음)"
  else
    log "$msg"
  fi
  exit 1
}
for ref in "$api_ref" "$web_ref" "$bundle_ref"; do
  docker pull -q "$ref" >/dev/null || pull_error "${ref##*/}"
done
if [[ -f .pull-error ]]; then
  rm -f .pull-error
  notify "이미지 가져오기 복구됨"
fi
# 같은 이미지가 여러 이름을 가질 수 있어 이 저장소(ref의 태그 앞부분) 이름의 digest만 고른다.
digest_of() {
  local repo="${1%:*}" d
  d="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$1" | grep -m1 "^${repo}@sha256:" || true)"
  [[ -n "$d" ]] || { notify "digest를 찾지 못했습니다: ${1##*/}"; exit 1; }
  printf '%s' "$d"
}
api_digest="$(digest_of "$api_ref")"
web_digest="$(digest_of "$web_ref")"
bundle_digest="$(digest_of "$bundle_ref")"
want="$api_digest $web_digest $bundle_digest"

[[ "$want" == "$(cat .deployed 2>/dev/null || true)" ]] && exit 0
if [[ "$want" == "$(cat .failed 2>/dev/null || true)" ]]; then
  log "실패했던 릴리스라 다시 시도하지 않습니다(새 릴리스를 승인하면 진행)"
  exit 0
fi

# 서버 승인(PM 총괄 결정 2026-10-10): 무료 플랜 비공개 저장소라 GitHub Environments 승인을 쓸 수 없다.
# 운영은 사용자가 서버에서 pull/approve.sh로 승인한 릴리스(.approved)만 배포한다. 비우면 운영=required, 그 밖=auto.
APPROVAL="$(env_value DEPLOY_APPROVAL)"
if [[ -z "$APPROVAL" ]]; then
  [[ "$CHANNEL" == production ]] && APPROVAL=required || APPROVAL=auto
fi
if [[ "$APPROVAL" != auto && "$want" != "$(cat .approved 2>/dev/null || true)" ]]; then
  # 승인 대기 알림은 릴리스마다 한 번만 보낸다.
  if [[ "$want" != "$(cat .pending 2>/dev/null || true)" ]]; then
    printf '%s\n' "$want" > .pending
    a=${api_digest##*@sha256:}; w=${web_digest##*@sha256:}
    notify "새 릴리스 승인 대기: api=${a:0:12} web=${w:0:12}. 서버 브라우저 SSH에서 sudo ${STACK_DIR}/pull/approve.sh"
  else
    log "승인 대기 중"
  fi
  exit 0
fi

log "새 릴리스: api=${api_digest##*@} web=${web_digest##*@}"

# 1. 배포 번들 풀기. 실행 중인 스크립트가 깨지지 않게 새 디렉터리를 만든 뒤 이름만 바꾼다.
bundle_files=(compose.prod.yaml Caddyfile Caddyfile.cloudflare cloudflare backup pull monitor)
rm -rf .bundle.new .bundle.prev
mkdir .bundle.new .bundle.prev
cid="$(docker create "$bundle_digest" true)"
docker cp "$cid:/bundle/." .bundle.new/ >/dev/null
docker rm "$cid" >/dev/null
for f in "${bundle_files[@]}"; do
  [[ -e "$f" ]] && cp -a "$f" .bundle.prev/
done

swap_in() {
  local from="$1" f
  for f in "${bundle_files[@]}"; do
    [[ -e "$from/$f" ]] || continue
    rm -rf "$f.old"
    [[ -e "$f" ]] && mv "$f" "$f.old"
    cp -a "$from/$f" "$f"
    rm -rf "$f.old"
  done
}
swap_in .bundle.new

# (운영) 번들의 systemd 유닛이 서버에 설치된 것과 다르면 바꿔 넣고 다시 읽는다. 이미 설치된 유닛만 바꾸고 새로 설치하지는 않는다.
# 유닛 이름은 운영·스테이징이 같이 쓰므로, develop 번들(스테이징)이 운영 유닛을 덮어쓰지 않게 운영 채널에서만 한다.
sync_units() {
  [[ "$CHANNEL" == production ]] || return 0
  local unit_dir="${SYSTEMD_UNIT_DIR:-/etc/systemd/system}" src name changed=()
  for src in pull/*.service pull/*.timer monitor/*.service monitor/*.timer backup/*.service backup/*.timer; do
    [[ -f "$src" ]] || continue
    name="$(basename "$src")"
    [[ -f "$unit_dir/$name" ]] || continue
    cmp -s "$src" "$unit_dir/$name" && continue
    install -m 0644 "$src" "$unit_dir/$name"
    changed+=("$name")
  done
  if ((${#changed[@]})); then
    systemctl daemon-reload
    notify "systemd 유닛 갱신: ${changed[*]}"
  fi
}

env_files=(--env-file stack.env)
compose() { docker compose -f compose.prod.yaml "${env_files[@]}" --env-file images.env "$@"; }
# 서비스 배포·되돌리기용: 모니터링 프로필을 끈 채로 올린다(셸 환경 변수가 --env-file 값을 이긴다).
# 모니터링이 헬스에 실패해도 서비스 배포가 실패·되돌림으로 번지지 않게 한다. 이미 떠 있는 모니터링 컨테이너는 그대로 둔다.
compose_app() { COMPOSE_PROFILES='' docker compose -f compose.prod.yaml "${env_files[@]}" --env-file images.env "$@"; }

# 모니터링(프로필 monitor)을 켠 서버에서만: prometheus·grafana는 설정 디렉터리(./monitor/…)를 통째로 붙이는데,
# swap_in이 옛 디렉터리를 지우면 실행 중 컨테이너에는 빈 디렉터리가 남는다. 그래서 배포마다 둘을 다시 만든다.
# 모니터링이 실패해도 서비스 배포는 되돌리지 않는다(알림만).
recreate_monitor() {
  [[ ",$(env_value COMPOSE_PROFILES)," == *",monitor,"* ]] || return 0
  # cloudflared는 번들 파일을 붙이지 않아 다시 만들 필요가 없고, 없을 때만 올린다.
  if compose up -d --wait --wait-timeout 180 --no-deps --force-recreate prometheus node-exporter grafana \
    && compose up -d --wait --wait-timeout 120 --no-deps cloudflared; then
    log "모니터링(prometheus·node-exporter·grafana)을 새 설정으로 다시 만들었다"
  else
    notify "모니터링 다시 만들기 실패(서비스 배포는 끝남). 서버에서 compose ps로 prometheus·grafana 확인"
  fi
}

# 2. 배포 전 DB 백업(실행 중일 때만).
mkdir -p backups
if [[ -f images.env ]] && [[ -n "$(compose ps -q postgres 2>/dev/null)" ]]; then
  # shellcheck disable=SC2016  # 변수는 postgres 컨테이너 안에서 펼친다.
  compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
    > "backups/pre-deploy-$(date -u +%Y%m%dT%H%M%SZ).dump"
  find backups -maxdepth 1 -name 'pre-deploy-*.dump' -type f -printf '%T@ %p\n' 2>/dev/null \
    | sort -rn | tail -n +15 | cut -d' ' -f2- | xargs -r rm --
fi

# 3. 이미지를 digest로 고정해 올린다.
[[ -f images.env ]] && cp images.env images.prev.env
printf 'API_IMAGE=%s\nWEB_IMAGE=%s\n' "$api_digest" "$web_digest" > images.env

if compose_app up -d --wait --wait-timeout 300 --remove-orphans; then
  printf '%s\n' "$want" > .deployed
  rm -f .failed .pending
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$want" >> deploy-history.log
  notify "배포 완료: api=${api_digest##*@sha256:} web=${web_digest##*@sha256:}"
  sync_units || log "systemd 유닛 갱신 실패(배포는 끝남). 수동: 운영 문서 deploy pull 배포 설치"
  recreate_monitor
  exit 0
fi

# 4. 실패: 직전 이미지·번들로 되돌린다.
printf '%s\n' "$want" > .failed
if [[ -f images.prev.env ]]; then
  cp images.prev.env images.env
  swap_in .bundle.prev
  if compose_app up -d --wait --wait-timeout 300 --remove-orphans; then
    notify "배포 실패 → 직전 릴리스로 되돌림(헬스 확인 통과). 실패 릴리스는 다시 시도하지 않음"
  else
    notify "배포 실패, 되돌리기도 실패. 즉시 확인 필요(운영 문서 deploy 롤백)"
  fi
  # 되돌리기도 설정 디렉터리를 바꾸므로 모니터링을 다시 붙인다(켠 서버만).
  recreate_monitor
else
  notify "첫 배포 실패. 되돌릴 이전 릴리스가 없음. 즉시 확인 필요"
fi
exit 1
