#!/bin/sh
# 저장소 공용 훅 디렉터리(.git/hooks)에 pre-push 훅을 설치한다. 같은 저장소의 작업 트리(wt/*) 모두에 적용된다.
# 사용: sh scripts/git-hooks/install.sh   (저장소나 작업 트리 어디서든)
set -eu

here=$(cd "$(dirname "$0")" && pwd)
hooks="$(git rev-parse --path-format=absolute --git-common-dir)/hooks"

if [ -n "$(git config --get core.hooksPath || true)" ]; then
  echo "core.hooksPath가 설정돼 있어 .git/hooks가 쓰이지 않습니다: $(git config --get core.hooksPath)" >&2
  exit 1
fi

mkdir -p "$hooks"
if [ -f "$hooks/pre-push" ] && ! cmp -s "$here/pre-push" "$hooks/pre-push"; then
  cp "$hooks/pre-push" "$hooks/pre-push.bak"
  echo "기존 pre-push를 pre-push.bak로 남겼습니다."
fi
# Windows 체크아웃(CRLF)이어도 sh가 읽을 수 있게 줄 끝을 LF로 맞춘다.
tr -d '\r' < "$here/pre-push" > "$hooks/pre-push"
chmod +x "$hooks/pre-push"
echo "설치했습니다: $hooks/pre-push"
