#!/usr/bin/env bash
# 백업 복원 확인(복구 연습). 덤프 파일을 임시 PostgreSQL 컨테이너에 복원하고 기본 상태를 확인한 뒤 지운다.
# 운영 DB에는 손대지 않는다. 운영 망에 붙지 않는 별도 컨테이너를 쓴다.
#
#   deploy/backup/restore-check.sh backups/daily/loakraken-20261010T193000Z.dump
#
# 외부 저장소의 백업을 확인하려면 먼저 rclone으로 내려받는다(운영 문서 backup).
set -euo pipefail

DUMP="${1:?덤프 파일 경로가 필요합니다}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:18.6}"
NAME="lk-restore-check-$$"

[[ -s "$DUMP" ]] || { echo "덤프 파일이 없거나 비어 있습니다: $DUMP" >&2; exit 1; }
# Git Bash(Windows)에서는 Docker가 알아듣는 C:/ 형식이 필요하다(pwd -W). Linux에서는 pwd를 쓴다.
DUMP_DIR="$(cd "$(dirname "$DUMP")" && { pwd -W 2>/dev/null || pwd; })"
DUMP_FILE="$(basename "$DUMP")"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# 임시 DB 암호는 매번 새로 만들고 컨테이너와 함께 사라진다.
docker run -d --name "$NAME" --network none \
  -e POSTGRES_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -d '/+=')" \
  -e POSTGRES_DB=restore_check -e POSTGRES_USER=restore \
  --mount "type=bind,src=$DUMP_DIR,dst=/dump,readonly" "$POSTGRES_IMAGE" >/dev/null

for _ in $(seq 1 60); do
  docker exec "$NAME" pg_isready -U restore -d restore_check >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$NAME" pg_isready -U restore -d restore_check >/dev/null

echo "복원 시작: $DUMP_FILE"
docker exec "$NAME" pg_restore -U restore -d restore_check --no-owner --no-privileges --exit-on-error "/dump/$DUMP_FILE"

psql() { docker exec "$NAME" psql -U restore -d restore_check -v ON_ERROR_STOP=1 -At "$@"; }

tables="$(psql -c "select count(*) from information_schema.tables where table_schema = 'public'")"
echo "public 테이블 수: $tables"
[[ "$tables" -gt 0 ]] || { echo "복원된 테이블이 없습니다" >&2; exit 1; }

if [[ "$(psql -c "select to_regclass('public.flyway_schema_history') is not null")" == "t" ]]; then
  echo "Flyway 최신 버전: $(psql -c "select version || ' (' || description || ')' from flyway_schema_history where success order by installed_rank desc limit 1")"
  failed="$(psql -c "select count(*) from flyway_schema_history where not success")"
  [[ "$failed" == "0" ]] || { echo "실패한 마이그레이션 기록: $failed" >&2; exit 1; }
fi

psql -c "analyze" >/dev/null
echo "테이블별 행 수:"
psql -F ' ' -c "select relname, n_live_tup from pg_stat_user_tables order by relname" \
  | while read -r t n; do printf '  %-40s %s\n' "$t" "$n"; done
echo "복원 확인 성공"
