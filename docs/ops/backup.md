# 운영 DB 외부 정기 백업과 복구 연습

운영 서버 디스크에만 있는 배포 전 백업(`backups/pre-deploy-*.dump`)은 서버를 잃으면 함께 사라진다. 그래서 매일 서버 밖 저장소로 **암호화한** 백업을 보낸다. 저장소 계정 생성·결제·비밀값 입력은 사용자가 직접 한다.

## 구성

| 파일 | 역할 |
|---|---|
| `deploy/backup/backup.sh` | `pg_dump`(custom 형식) → rclone crypt로 암호화 업로드 → `cryptcheck` 대조 → 보관 기간 지난 원격 백업 삭제 → 로컬 사본 7일만 유지 |
| `deploy/backup/restore-check.sh` | 덤프를 임시 PostgreSQL(망 없음)에 복원하고 테이블 수·Flyway 최신 버전·실패 마이그레이션·테이블별 행 수를 확인한 뒤 지운다 |
| `deploy/backup/rclone.conf.example` | S3 호환 저장소(`offsite`) + 암호화 계층(`offsite-crypt`) 설정 형식. 값 없음 |
| `deploy/backup/loakraken-backup.service`·`.timer` | systemd로 매일 04:30(한국 시간) 실행. 꺼져 있었으면 켜진 뒤 한 번 |

배포 워크플로가 `deploy/backup`을 `/opt/loakraken/<환경>/backup/`으로 올린다.

## 보관 기간

- 기본 **30일**(`BACKUP_RETENTION_DAYS`). 스크립트가 **180일을 넘는 값은 거부**한다.
- **개인정보 처리방침에 적은 백업 보유 기간과 같게** 한다. 180일은 스크립트의 안전 상한일 뿐 목표값이 아니다. 처리방침이 바뀌면 이 값을 함께 바꾼다.
- 백업 대상은 운영 DB(정규화된 관측·이력·조회수·랭킹 등)뿐이다. 공식 API 원문 데이터는 저장하지 않으므로 백업에도 없다(PM 총괄 결정 2026-10-09).
- **삭제 요청과 백업**: 개인정보 삭제 요청(`privacy@`)은 처리방침에 정한 기한 안에 운영 DB에서 처리하지만, 그 전에 뜬 외부 백업에는 보관 기간(기본 30일)이 끝날 때까지 남는다. 처리방침에 이 문구가 필요하다(PM 총괄 전달함에 올림). 백업에서 복구하면 아래 "복구 시 삭제 요청 다시 적용"을 반드시 한다.
- 서버 로컬 사본은 7일(`LOCAL_RETENTION_DAYS`), 배포 전 백업은 최근 14개.
- 원격 삭제는 업로드 시각이 아니라 백업 파일 시각 기준이다(rclone이 원본 시각을 보존).

## 저장소 선택

- 서버와 **다른 사업자 또는 다른 지역**의 S3 호환 저장소를 쓴다. 같은 계정·지역이면 계정 정지나 지역 장애 때 함께 잃는다.
- 후보: 서버 사업자의 객체 저장소(Lightsail 버킷·Vultr Object Storage), Backblaze B2, Cloudflare R2. 가격은 서버 결정 때 공식 페이지로 확인한다.
- DB 크기가 작아(현재 수 MB 수준 추정) 비용은 월 1달러 안팎일 것으로 본다(추정).
- 백업에는 개인정보가 들어 있을 수 있으므로 저장소 사업자를 처리방침의 처리 위탁·국외 이전 목록에 넣는다.

## 설치 (서버 결정 후, 사용자와 함께)

1. 저장소에 버킷을 만들고, 그 버킷만 읽고 쓸 수 있는 접근 키를 만든다(사용자).
2. 서버에서 `rclone.conf`를 만든다(사용자). 형식은 `rclone.conf.example`을 따른다.
   - crypt 비밀번호 두 개는 새로 만들고 `rclone obscure`로 넣는다.
   - **이 두 비밀번호는 서버 밖(비밀번호 관리자)에도 보관한다. 잃으면 백업을 복원할 수 없다.**
   - 위치: `/opt/loakraken/production/secrets/rclone.conf`, `sudo chmod 400`. 디렉터리는 root 전용(700)이다. 스크립트는 파일을 직접 읽지 않고 Docker가 마운트한다.
3. systemd 등록(배포·운영 안내, 사용자 실행)

```bash
sudo cp /opt/loakraken/production/backup/loakraken-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now loakraken-backup.timer
sudo systemctl start loakraken-backup.service      # 첫 실행
journalctl -u loakraken-backup.service -n 30         # 결과 확인
```

## 복구 연습 (출시 전 1회, 이후 분기 1회)

```bash
cd /opt/loakraken/production
mkdir -p restore-drill
docker run --rm --mount type=bind,src=$PWD/secrets/rclone.conf,dst=/config/rclone/rclone.conf,readonly \
  --mount type=bind,src=$PWD/restore-drill,dst=/out rclone/rclone:1.75.1 \
  copy offsite-crypt:daily /out --max-age 2d
backup/restore-check.sh restore-drill/<파일 이름>.dump
rm -rf restore-drill
```

- 확인 기준: "복원 확인 성공", Flyway 최신 버전이 운영과 같음, 주요 테이블 행 수가 운영과 비슷함.
- 결과(날짜·파일·걸린 시간)를 볼트 `80 배포·운영`에 기록한다.
- 실제 장애 복구는 운영 스택을 멈춘 뒤 `pg_restore --clean`으로 되돌리는 별도 절차가 필요하다. 첫 복구 연습 때 함께 문서로 만든다.

### 복구 시 삭제 요청 다시 적용

'순위 제외·캐릭터 기록 삭제 요청' 서비스는 2026-10-11 사용자 결정으로 없앴다(PM 총괄 21:37). 개인정보 일반 권리 요청(열람·정정·삭제·처리정지, `privacy@`)만 남고, 처리정지·삭제 요청은 운영자가 **처리정지 목록**(`public_exclusion` 표, 통계·랭킹·길드 제외 + 재수집 금지)에 손으로 반영한다(PM 총괄 21:39).

백업 시점 이후에 처리한 개인정보 삭제·처리정지 요청은 복원한 DB에 다시 살아난다. 그래서 복구 직후, 서비스를 다시 열기 전에 다음을 한다.

1. 개인정보 메일(`privacy@`)과 처리 기록에서 **백업 시각 이후에 처리한 삭제·처리정지 요청 목록**을 뽑는다.
2. 각 요청을 다시 적용한다(처리정지 목록에 다시 넣기, 해당 캐릭터 데이터 삭제 등). 명령은 [privacy-stop-list.md](privacy-stop-list.md)를 따른다.
3. 다시 적용한 건수와 시각을 볼트 `80 배포·운영`에 기록한다.

- 이를 위해 삭제·처리정지 요청은 처리할 때마다 **처리 시각과 대상(캐릭터명·서버)을 기록**해 둔다. 기록 위치는 처리방침·문의 절차와 함께 PM 총괄이 정한다.

## 실패 알림

- 실패하면 종료 코드가 1이고 `journalctl`에 "실패:" 줄이 남는다.
- 알림 연결은 감시 설계(배포·운영 `감시와 알림 설계`)에서 정한다. 그 전까지는 배포 후 관찰 때 `systemctl status loakraken-backup.service`를 확인한다.

## 로컬 검증 기록 (2026-10-09, #119)

- 임시 스택의 postgres(운영 compose)와 `rclone serve s3` 기반 로컬 S3 서버로 실행했다.
  - `backup.sh` 종료 코드 0. `cryptcheck` 결과 일치 1건, 차이 0건.
  - 원본 저장소에는 파일·디렉터리 이름까지 암호화된 형태로만 보였다.
  - 보관 기간 200일은 "1~180일이어야 합니다"로 거부됐다(종료 코드 1).
- crypt 원격에서 내려받아 `restore-check.sh`를 돌렸다: 테이블 2개, Flyway 7, 행 250·1. 임시 컨테이너는 남지 않았다.
- shellcheck 지적 없음(컨테이너 안에서 펼치는 의도된 1줄만 예외).
