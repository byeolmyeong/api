#!/usr/bin/env bash
# 운영 DB 를 덤프해 VM 에 7개 남기고 Object Storage 에 올린다(결정 020). 운영 VM 의 cron 이 매일 04:00 에 돌린다.
# 같은 디렉터리의 .env 에서 DATABASE_USERNAME·BACKUP_UPLOAD_URL 두 값만 읽는다. URL·비밀번호는 출력하지 않는다.
# 결과는 한 줄: event=backup_done(파일·바이트) 또는 event=backup_failed(단계). 실패하면 0 이 아닌 값으로 끝난다.
set -euo pipefail

KEEP=7
cd "$(dirname "$0")"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) event=$*"; }
fail() { log "backup_failed step=$1${2:+ $2}"; exit 1; }

# .env 를 셸로 실행하지 않고 필요한 이름의 값만 꺼낸다.
env_value() { grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- || true; }
DB_USER="$(env_value DATABASE_USERNAME)"
UPLOAD_URL="$(env_value BACKUP_UPLOAD_URL)"
[ -n "$DB_USER" ] || fail read_env "name=DATABASE_USERNAME"
[ -n "$UPLOAD_URL" ] || fail read_env "name=BACKUP_UPLOAD_URL"

mkdir -p backups
NAME="byeolmyeong-$(date -u +%Y%m%dT%H%M%SZ).dump"
PART="backups/.$NAME.part"
if ! docker compose exec -T postgres pg_dump -U "$DB_USER" -d byeolmyeong -Fc > "$PART"; then
	rm -f "$PART"
	fail dump
fi
mv "$PART" "backups/$NAME"
BYTES="$(wc -c < "backups/$NAME" | tr -d ' ')"

# 이름에 시각이 들어 있어 이름순이 곧 시간순이다. 새것부터 KEEP 개를 남긴다.
find backups -maxdepth 1 -name 'byeolmyeong-*.dump' | sort -r | tail -n +$((KEEP + 1)) | while read -r old; do rm -f "$old"; done

# 사전 인증 URL(끝이 /o/)에 객체 이름을 붙여 PUT 한다. curl 의 오류 문장은 버리고 종료 코드만 남긴다(URL 이 섞일 수 있다).
code=0
curl -fsS --retry 3 --max-time 600 -T "backups/$NAME" "$UPLOAD_URL$NAME" -o /dev/null 2>/dev/null || code=$?
[ "$code" -eq 0 ] || fail upload "file=$NAME curl_exit=$code"

log "backup_done file=$NAME bytes=$BYTES"
