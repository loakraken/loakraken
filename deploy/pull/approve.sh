#!/usr/bin/env bash
# 운영 릴리스 승인(PM 총괄 결정 2026-10-10). 사용자가 서버(Lightsail 브라우저 SSH)에서 직접 실행한다.
#   sudo /opt/loakraken/production/pull/approve.sh
#
# 무료 플랜 비공개 저장소라 GitHub Environments 승인자를 쓸 수 없다. 그래서 Deploy 워크플로가 채널 태그를 옮겨도
# 운영 서버는 여기서 승인한 릴리스(.approved)만 배포한다(pull-deploy.sh). Claude 세션은 서버 콘솔에 들어갈 수 없다.
#
# 하는 일: 채널 태그의 새 릴리스와 지금 배포된 릴리스의 커밋·빌드 시각·비교 링크를 보여 주고 y/N으로 묻는다.
# y면 승인을 기록하고 바로 배포를 실행한 뒤 결과를 보여 준다. 브라우저 SSH에서 한글이 깨지므로 출력은 영문이다.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Run with sudo: sudo $0"; exit 1; }

STACK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$STACK_DIR"
[[ -f stack.env ]] || { echo "stack.env not found in $STACK_DIR"; exit 1; }
env_value() { sed -n "s/^$1=//p" stack.env | tail -n 1; }
CHANNEL="$(env_value DEPLOY_CHANNEL)"
REGISTRY="$(env_value DEPLOY_REGISTRY)"
REGISTRY="${REGISTRY:-ghcr.io/loakraken}"
ENV_NAME="$(basename "$STACK_DIR")"
[[ "$CHANNEL" =~ ^(production|staging)$ ]] || { echo "DEPLOY_CHANNEL must be production or staging"; exit 1; }

refs=("$REGISTRY/loakraken-api:$CHANNEL" "$REGISTRY/loakraken-web:$CHANNEL" "$REGISTRY/loakraken-deploy:$CHANNEL")
names=(api web bundle)

echo "Checking the $CHANNEL channel..."
for ref in "${refs[@]}"; do
  docker pull -q "$ref" >/dev/null || { echo "PULL_FAILED: ${ref##*/} (check 'sudo docker login ghcr.io')"; exit 1; }
done
digest_of() {
  local repo="${1%:*}"
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$1" | grep -m1 "^${repo}@sha256:" || true
}
new=()
for ref in "${refs[@]}"; do
  d="$(digest_of "$ref")"
  [[ -n "$d" ]] || { echo "DIGEST_NOT_FOUND: ${ref##*/}"; exit 1; }
  new+=("$d")
done
want="${new[*]}"

deployed="$(cat .deployed 2>/dev/null || true)"
if [[ "$want" == "$deployed" ]]; then
  echo "Already deployed. Nothing to approve."
  exit 0
fi
read -r -a cur <<<"$deployed"
# 지금 배포된 이미지는 digest로 받아 둔 것이라 대개 로컬에 있다. 없으면 digest로 다시 받아 라벨을 읽는다.
for d in "${cur[@]}"; do
  docker image inspect "$d" >/dev/null 2>&1 || docker pull -q "$d" >/dev/null 2>&1 || true
done

label() { docker image inspect --format "{{index .Config.Labels \"$2\"}}" "$1" 2>/dev/null || true; }
short() { [[ -n "$1" && "$1" != "<no value>" ]] && printf '%s' "${1:0:7}" || printf '%s' "-"; }

echo
echo "LOAKraken $CHANNEL release approval"
printf '%-7s %-10s %-10s %s\n' "part" "current" "new" "new build time (UTC)"
for i in 0 1 2; do
  new_rev="$(label "${new[$i]}" org.opencontainers.image.revision)"
  new_at="$(label "${new[$i]}" org.opencontainers.image.created)"
  cur_rev=""
  [[ -n "${cur[$i]:-}" ]] && cur_rev="$(label "${cur[$i]}" org.opencontainers.image.revision)"
  printf '%-7s %-10s %-10s %s\n' "${names[$i]}" "$(short "$cur_rev")" "$(short "$new_rev")" "${new_at:--}"
done

echo
echo "Changes (open in a browser):"
for i in 0 1; do
  src="$(label "${new[$i]}" org.opencontainers.image.source)"
  new_rev="$(label "${new[$i]}" org.opencontainers.image.revision)"
  cur_rev=""
  [[ -n "${cur[$i]:-}" ]] && cur_rev="$(label "${cur[$i]}" org.opencontainers.image.revision)"
  [[ -n "$src" && "$src" != "<no value>" && -n "$new_rev" && "$new_rev" != "<no value>" ]] || { echo "  ${names[$i]}: (no source label)"; continue; }
  if [[ -n "$cur_rev" && "$cur_rev" != "<no value>" && "$cur_rev" != "$new_rev" ]]; then
    echo "  ${names[$i]}: $src/compare/$cur_rev...$new_rev"
  else
    echo "  ${names[$i]}: $src/commit/$new_rev"
  fi
done

if [[ "$want" == "$(cat .failed 2>/dev/null || true)" ]]; then
  echo
  echo "WARNING: this release failed before and was rolled back. Approving will try it again."
fi

echo
read -r -p "Approve this release for $CHANNEL? [y/N] " answer
case "$answer" in
  y | Y | yes | YES) ;;
  *)
    echo "NOT_APPROVED"
    exit 0
    ;;
esac

printf '%s\n' "$want" > .approved
[[ "$want" == "$(cat .failed 2>/dev/null || true)" ]] && rm -f .failed
printf '%s approved %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$want" >> deploy-history.log
echo "APPROVED"

unit="loakraken-pull-deploy@${ENV_NAME}.service"
if systemctl cat "$unit" >/dev/null 2>&1; then
  # 유닛 파일이 디스크에서 바뀌었으면(설치 파일을 다시 복사한 경우 등) 시작 전에 다시 읽는다. 사용자가 따로 daemon-reload를 하지 않아도 된다.
  for u in "$unit" "loakraken-pull-deploy@${ENV_NAME}.timer"; do
    if [[ "$(systemctl show -p NeedDaemonReload --value "$u" 2>/dev/null)" == yes ]]; then
      systemctl daemon-reload
      echo "Reloaded systemd unit files (they changed on disk)."
      break
    fi
  done
  echo "Deploying now (this can take a few minutes)..."
  systemctl start "$unit" || true
  if [[ "$want" == "$(cat .deployed 2>/dev/null || true)" ]]; then
    echo "DEPLOY_OK"
  else
    echo "DEPLOY_NOT_DONE: check 'sudo journalctl -u $unit -n 50'"
  fi
else
  echo "The pull timer is not installed. Run: sudo systemctl start $unit after installing it."
fi
