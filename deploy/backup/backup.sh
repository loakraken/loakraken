#!/usr/bin/env bash
# LOAKraken 운영 DB 외부 정기 백업(출시 점검표 R14).
#
# 서버의 스택 디렉터리(예: /opt/loakraken/production)에서 실행한다.
#   1. 실행 중인 postgres 컨테이너에서 pg_dump(custom 형식)를 뜬다.
#   2. rclone crypt 원격으로 서버 밖 S3 호환 저장소에 암호화해 올린다.
#   3. 보관 기간이 지난 원격 백업을 지우고, 로컬 사본은 짧게만 둔다.
#
# 비밀값(저장소 키, 암호화 비밀번호)은 ${SECRETS_DIR}/rclone.conf에만 있다. 이 스크립트·저장소·로그에 남기지 않는다.
# 보관 기간은 개인정보 처리방침 보유 기간에 맞추며 180일을 넘길 수 없다.
set -euo pipefail

STACK_DIR="${STACK_DIR:-$(pwd)}"
BACKUP_REMOTE="${BACKUP_REMOTE:-offsite-crypt}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
LOCAL_RETENTION_DAYS="${LOCAL_RETENTION_DAYS:-7}"
RCLONE_IMAGE="${RCLONE_IMAGE:-rclone/rclone:1.75.1}"
RCLONE_NETWORK="${RCLONE_NETWORK:-}"
MAX_RETENTION_DAYS=180

log() { printf '%s backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
fail() { log "실패: $*"; exit 1; }

[[ "$BACKUP_RETENTION_DAYS" =~ ^[0-9]+$ ]] || fail "BACKUP_RETENTION_DAYS는 숫자여야 합니다"
(( BACKUP_RETENTION_DAYS >= 1 && BACKUP_RETENTION_DAYS <= MAX_RETENTION_DAYS )) \
  || fail "보관 기간은 1~${MAX_RETENTION_DAYS}일이어야 합니다(현재 ${BACKUP_RETENTION_DAYS})"
[[ "$LOCAL_RETENTION_DAYS" =~ ^[0-9]+$ ]] || fail "LOCAL_RETENTION_DAYS는 숫자여야 합니다"

cd "$STACK_DIR"
[[ -f stack.env ]] || fail "stack.env가 없습니다($STACK_DIR)"
# SECRETS_DIR·STACK_NAME만 읽는다(값을 출력하지 않는다).
SECRETS_DIR="$(sed -n 's/^SECRETS_DIR=//p' stack.env | tail -n 1)"
STACK_NAME="$(sed -n 's/^STACK_NAME=//p' stack.env | tail -n 1)"
# SECRETS_DIR은 root 전용(700)이다. rclone.conf는 이 스크립트가 직접 읽지 않고 Docker 데몬이 마운트한다(파일이 없으면 --mount가 실패).
# 직접 읽는 것은 아래 notify의 경보 웹훅 파일 하나뿐이다(root로 실행, 내용은 출력하지 않음).
[[ -n "$SECRETS_DIR" ]] || fail "stack.env에 SECRETS_DIR가 없습니다"

# 알림: pull-deploy·host-check와 같은 Slack 경보 웹훅(root만 읽는 파일). 웹훅 주소는 출력하지 않고, 보내기에 실패해도 멈추지 않는다.
notify() {
  local hook="${SECRETS_DIR}/slack_webhook_alerts" text="[${STACK_NAME:-loakraken}/백업] $1"
  if [[ -r "$hook" ]]; then
    curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
      --data "$(printf '{"text":"%s"}' "${text//\"/\'}")" "$(cat "$hook")" >/dev/null || log "Slack 알림 실패"
  fi
  log "$1"
}

# 서버 안 배포 전 덤프(pre-deploy-*, 손으로 뜬 pre-infra-* 등 pre-*)의 30일 기간 상한. pull-deploy도 배포 때 같은 줄을 돌리지만,
# 배포가 없는 날에도 지워지도록 매일 도는 이 스크립트가 맨 앞에서 돌린다(뒤 단계가 실패해도 먼저 끝남). 처리방침 "백업 최대 30일".
# 실패해도 백업은 계속한다. 오류는 로그(journal)에 남고 Slack으로 알린다.
if [[ -d backups ]] && ! find backups -maxdepth 1 -name 'pre-*.dump' -type f -mmin +43200 -delete; then
  notify "30일 지난 배포 전 덤프 지우기 실패(백업은 계속). 서버 backups/ 권한·디스크 확인"
fi

env_files=(--env-file stack.env)
[[ -f images.env ]] && env_files+=(--env-file images.env)
compose() { docker compose -f compose.prod.yaml "${env_files[@]}" "$@"; }

[[ -n "$(compose ps -q postgres 2>/dev/null)" ]] || fail "postgres 컨테이너가 실행 중이 아닙니다"

mkdir -p backups/daily
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="${STACK_NAME:-loakraken}-${stamp}.dump"
tmp="backups/daily/.${name}.partial"
trap 'rm -f "$tmp"' EXIT

log "pg_dump 시작"
# 변수는 postgres 컨테이너 안에서 펼친다(호스트에서 펼치지 않음).
# shellcheck disable=SC2016
compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=6' > "$tmp"
[[ -s "$tmp" ]] || fail "덤프 파일이 비어 있습니다"
mv "$tmp" "backups/daily/$name"
size="$(wc -c < "backups/daily/$name" | tr -d ' ')"
log "pg_dump 완료: $name (${size} bytes)"

rclone() {
  docker run --rm ${RCLONE_NETWORK:+--network "$RCLONE_NETWORK"} \
    --mount "type=bind,src=$SECRETS_DIR/rclone.conf,dst=/config/rclone/rclone.conf,readonly" \
    --mount "type=bind,src=$STACK_DIR/backups/daily,dst=/backups,readonly" \
    "$RCLONE_IMAGE" "$@"
}

log "외부 저장소 업로드 시작(${BACKUP_REMOTE})"
rclone copyto "/backups/$name" "${BACKUP_REMOTE}:daily/$name" --retries 3 --low-level-retries 5
# crypt 원격은 해시를 직접 비교할 수 없어 cryptcheck로 원본과 대조한다.
rclone cryptcheck "/backups" "${BACKUP_REMOTE}:daily" --one-way --include "$name"
log "업로드·검증 완료"

log "원격 보관 기간 정리(${BACKUP_RETENTION_DAYS}일 초과 삭제)"
rclone delete "${BACKUP_REMOTE}:daily" --min-age "${BACKUP_RETENTION_DAYS}d"

find backups/daily -maxdepth 1 -name '*.dump' -type f -mtime +"$LOCAL_RETENTION_DAYS" -delete
log "완료"
