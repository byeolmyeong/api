#!/usr/bin/env bash
# 백업 덤프 하나를 복원한다(결정 020). VM 의 /opt/byeolmyeong 에서 돌린다.
#   ./restore.sh <덤프>              일회용 컨테이너(postgres:17)에 복원하고 표마다 행 수를 출력한 뒤 지운다. 실제 DB 는 건드리지 않는다.
#   ./restore.sh <덤프> --into-live  실제 DB 를 덤프 내용으로 바꾼다. 확인 문구를 받고, 그동안 서버를 멈춘다. 되돌릴 수 없다.
# 운영 덤프는 운영 VM 밖(스테이징·개인 컴퓨터)으로 옮기지 않는다. 출생 정보가 들어 있다.
set -euo pipefail

cd "$(dirname "$0")"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) event=$*"; }
fail() { log "restore_failed step=$1"; exit 1; }
usage() { echo "사용법: $0 <덤프 파일> [--into-live]" >&2; exit 2; }

DUMP="${1:-}"
TARGET="${2:-check}"
if [ -z "$DUMP" ] || [ ! -f "$DUMP" ]; then usage; fi
case "$TARGET" in check | --into-live) ;; *) usage ;; esac

# public 스키마의 표마다 정확한 행 수를 "표|행 수" 로 출력한다.
COUNT_SQL="select format('select %L, count(*) from public.%I', table_name, table_name)
  from information_schema.tables
  where table_schema = 'public' and table_type = 'BASE TABLE'
  order by table_name \\gexec"

if [ "$TARGET" = check ]; then
	NAME="byeolmyeong-restore-check-$$"
	trap 'docker rm -f "$NAME" > /dev/null 2>&1 || true' EXIT
	docker run -d --name "$NAME" -e POSTGRES_PASSWORD="check-$$-$RANDOM" -e POSTGRES_DB=restore postgres:17 > /dev/null || fail start
	for _ in $(seq 1 60); do
		# 이미지의 초기화 스크립트가 끝나 서버가 다시 뜬 뒤에야 TCP 로 받는다.
		docker exec "$NAME" pg_isready -h 127.0.0.1 -U postgres -d restore > /dev/null 2>&1 && break
		sleep 1
	done
	docker exec "$NAME" pg_isready -h 127.0.0.1 -U postgres -d restore > /dev/null 2>&1 || fail wait
	docker exec -i "$NAME" pg_restore -U postgres -d restore --no-owner --no-privileges --exit-on-error < "$DUMP" || fail pg_restore
	docker exec -i "$NAME" psql -U postgres -d restore -tA -v ON_ERROR_STOP=1 <<< "$COUNT_SQL" || fail count
	log "restore_checked file=$(basename "$DUMP")"
	exit 0
fi

DB_USER="$(grep -E '^DATABASE_USERNAME=' .env | tail -n 1 | cut -d= -f2- || true)"
[ -n "$DB_USER" ] || fail read_env
echo "실제 DB(byeolmyeong)를 $(basename "$DUMP") 의 내용으로 바꿉니다. 지금 DB 의 내용은 사라집니다."
read -r -p "계속하려면 'restore byeolmyeong' 을 입력하세요: " answer
[ "$answer" = "restore byeolmyeong" ] || { echo "취소했습니다."; exit 1; }

docker compose stop api
# 실패해도 서버는 다시 띄운다. 어떤 상태인지는 출력과 이벤트로 남긴다.
trap 'docker compose start api > /dev/null' EXIT
docker compose exec -T postgres pg_restore -U "$DB_USER" -d byeolmyeong --clean --if-exists --no-owner --exit-on-error < "$DUMP" || fail pg_restore
log "restore_live_done file=$(basename "$DUMP")"
