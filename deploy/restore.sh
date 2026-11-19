#!/usr/bin/env bash
# 백업 덤프 하나를 복원한다(결정 020). VM 의 /opt/byeolmyeong 에 있고, 어디서 불러도 된다(덤프 경로는 부른 곳 기준).
#   ./restore.sh <덤프>              일회용 컨테이너(postgres:17)에 복원하고 표마다 행 수를 출력한 뒤 컨테이너와 볼륨을 지운다.
#                                    실제 DB 는 건드리지 않는다.
#   ./restore.sh <덤프> --into-live  실제 DB 를 덤프 내용으로 바꾼다. 확인 문구를 받고, 지금 DB 를 먼저 덤프해 두고, 서버를 멈춘다.
#                                    한 트랜잭션 안에서 public 스키마를 비우고 덤프를 넣는다. 덤프 뒤에 생긴 표도 남지 않는다.
#                                    중간에 실패하면 DB 는 원래대로 남고 서버는 다시 뜬다.
#                                    성공하면 서버는 멈춘 채로 둔다. 지금 jar 로 띄우면 그 jar 의 마이그레이션이 다시 적용될 수 있어서다.
#                                    같은 jar 로 띄우려면 `docker compose start api`, 이전 버전이면 그 커밋을 배포 수동 실행으로 올린다.
# 운영 덤프는 운영 VM 밖(스테이징·개인 컴퓨터)으로 옮기지 않는다. 출생 정보가 들어 있다.
# 복원 직전에 남긴 backups/before-restore-*.dump 는 자동으로 지워지지 않는다. 필요 없어지면 지운다.
set -euo pipefail

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) event=$*"; }
fail() { log "restore_failed step=$1${2:+ $2}"; exit 1; }
usage() { echo "사용법: $0 <덤프 파일> [--into-live]" >&2; exit 2; }

DUMP_ARG="${1:-}"
TARGET="${2:-check}"
if [ -z "$DUMP_ARG" ] || [ ! -f "$DUMP_ARG" ]; then usage; fi
case "$TARGET" in check | --into-live) ;; *) usage ;; esac
# 부른 곳 기준의 경로를 절대 경로로 고정한 뒤 스크립트 디렉터리(compose.yaml·.env 가 있는 곳)로 간다.
DUMP="$(cd "$(dirname "$DUMP_ARG")" && pwd)/$(basename "$DUMP_ARG")"
cd "$(dirname "$0")"

# public 스키마의 표마다 정확한 행 수를 "표|행 수" 로 출력한다.
COUNT_SQL="select format('select %L, count(*) from public.%I', table_name, table_name)
  from information_schema.tables
  where table_schema = 'public' and table_type = 'BASE TABLE'
  order by table_name \\gexec"

if [ "$TARGET" = check ]; then
	NAME="byeolmyeong-restore-check-$$"
	# -v: 이미지가 만드는 익명 볼륨(복원된 DB 사본)까지 지운다. 안 지우면 출생 정보 사본이 볼륨으로 계속 쌓인다.
	trap 'docker rm -f -v "$NAME" > /dev/null 2>&1 || true' EXIT
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
echo "실제 DB(byeolmyeong)를 $(basename "$DUMP") 의 내용으로 바꿉니다. 지금 DB 는 먼저 backups/ 에 덤프해 둡니다."
read -r -p "계속하려면 'restore byeolmyeong' 을 입력하세요: " answer
[ "$answer" = "restore byeolmyeong" ] || { echo "취소했습니다."; exit 1; }

# 덮어쓰기 전에 지금 DB 를 남긴다. 이게 안 되면 아무것도 바꾸지 않는다.
mkdir -p backups
BEFORE="backups/before-restore-$(date -u +%Y%m%dT%H%M%SZ).dump"
if ! docker compose exec -T postgres pg_dump -U "$DB_USER" -d byeolmyeong -Fc > "$BEFORE"; then
	rm -f "$BEFORE"
	fail dump_current
fi
log "restore_saved_current file=$(basename "$BEFORE")"

# 덤프를 Postgres 컨테이너 안에서 SQL 로 끝까지 풀어 본다. 잘린 덤프면 여기서 멈추고 서버도 DB 도 그대로다.
# 풀어 둔 파일에는 출생 정보가 들어 있으니 끝나면 반드시 지운다.
cleanup_tmp() { docker compose exec -T postgres rm -f /tmp/restore.dump /tmp/restore.sql > /dev/null 2>&1 || true; }
trap cleanup_tmp EXIT
docker compose exec -T postgres sh -c 'umask 077 && cat > /tmp/restore.dump && pg_restore --no-owner --no-privileges -f /tmp/restore.sql /tmp/restore.dump' < "$DUMP" || fail convert "db=unchanged"

docker compose stop api
RESTORED=0
# 실패하면 한 트랜잭션이 통째로 취소돼 DB 는 원래대로이므로 서버를 다시 띄운다. 성공하면 멈춘 채로 둔다(맨 위 설명).
trap 'cleanup_tmp; [ "$RESTORED" = 1 ] || docker compose start api > /dev/null' EXIT
# public 스키마를 지우고 처음 상태(소유자 pg_database_owner)로 다시 만든 뒤 덤프를 넣는다. 모두 한 트랜잭션이다.
docker compose exec -T postgres sh -c '{ echo "drop schema public cascade;"; echo "create schema public authorization pg_database_owner;"; cat /tmp/restore.sql; } | psql -q -v ON_ERROR_STOP=1 --single-transaction -U "$APP_DB_USER" -d byeolmyeong > /dev/null' || fail apply "db=unchanged"
RESTORED=1
log "restore_live_done file=$(basename "$DUMP") api=stopped"
echo "복원했습니다. 서버는 멈춰 있습니다. 같은 jar 로 띄우려면 docker compose start api, 이전 버전이면 그 커밋을 배포 수동 실행으로 올리세요."
