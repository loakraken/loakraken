# 로컬 Git 훅 (master·develop 보호)

조직 무료 플랜 + 비공개 저장소라 GitHub 브랜치 보호·룰셋을 쓸 수 없다(PM 총괄 2026-10-09). 그래서 로컬 `pre-push` 훅으로 실수를 막는다. 출시 뒤 공개·유료 전환 때 GitHub 룰셋으로 옮기고 이 훅은 지운다.

| 막는 것 | 대신 할 일 |
|---|---|
| master·develop로 직접 push | PR을 만들고 승인 뒤 `gh pr merge --squash --subject "…"`(서버에서 병합하므로 훅을 거치지 않음) |
| master·develop 원격 삭제 | 하지 않는다 |
| 강제 push(빨리 감기가 아닌 push) | 원격이 앞서 있으면 `git fetch` 뒤 rebase. PR 브랜치를 rebase했다면 `LOAKRAKEN_ALLOW_FORCE=1 git push --force-with-lease`(그 한 번만 허용, master·develop은 허용 안 함) |

## 설치 (저장소마다 1회)

```bash
sh scripts/git-hooks/install.sh
```

- 저장소 공용 `.git/hooks/pre-push`에 설치되어 같은 저장소의 작업 트리(`C:/LOAKraken/wt/*`) 모두에 적용된다.
- 훅을 바꾼 PR이 병합되면 다시 실행한다.
- `git push --no-verify`로 넘을 수 있다. 실수를 막는 장치이므로 규칙(커밋과 PR 규칙)은 그대로 지킨다.
