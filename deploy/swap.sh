#!/usr/bin/env bash
# 서버를 끊김 없이 바꿔 띄운다(블루 · 그린, 결정 046). VM 의 /opt/byeolmyeong 에 있고, 어디서 불러도 된다(jar 경로는 부른 곳 기준).
#   ./swap.sh <jar>       그 jar 를 쉬는 색으로 띄우고 받는 쪽을 바꾼다. 배포 워크플로는 app.jar.new.
#                         바로 앞 버전으로 되돌리기는 ./swap.sh app.jar.prev (지금 jar 의 앞에 받는 쪽이었던 jar).
#   ./swap.sh --restart   지금 받는 색의 jar 그대로 쉬는 색으로 다시 띄운다(.env 를 바꾼 뒤 — 새 컨테이너가 새 값을 읽는다).
#   ./swap.sh --init      처음 한 번: 받는 쪽 파일(upstream.caddy)을 api-blue 로 만든다. 이미 있으면 하지 않는다(docs/deploy.md (다)).
# 순서: ① 지금 색(Caddy 가 실제로 쓰는 색)과 쉬는 색 ② jar 를 쉬는 색 파일로 ③ 쉬는 색을 새로 만든다
#       ④ Caddy 안에서 그 색의 /health 가 200 일 때까지(최대 5분) ⑤ upstream.caddy 를 그 색으로 · caddy reload · 실제로 바뀌었는지 확인
#       ⑥ 5초 뒤 옛 색을 차분하게 내린다(받던 요청 · 쓰던 생성을 끝낸다, 최대 320초) ⑦ app.jar.good(지금) · app.jar.prev(그 앞)를 적는다.
# 받는 쪽을 바꾸기 전에 실패하면 쉬는 색을 내리고 끝낸다 — 받는 쪽은 옛 색 그대로다. Caddy 가 쓰는 색인지 모르면 아무 색도 내리지 않는다.
# Postgres · Caddy 는 만지지 않는다(--no-deps). 로그는 event=swap_* 한 줄씩. 실패하면 0 이 아닌 값으로 끝난다.
set -euo pipefail

# E2.1.Micro(1GB · 버스트 CPU)에서 두 JVM 이 겹치면 새 JVM 시작이 89~187초 걸렸다(2026-11-20 스테이징 8번). 넘으면 바꾸지 않고 실패한다.
HEALTH_WAIT_SECONDS=300
DRAIN_DELAY_SECONDS=5

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) event=$*"; }
fail() { log "swap_failed step=$1${2:+ $2}" >&2; exit 1; }
usage() { echo "사용법: $0 <jar 경로> | --restart | --init" >&2; exit 2; }

[ $# -eq 1 ] || usage
MODE=jar
SRC=""
case "$1" in
	--restart) MODE=restart ;;
	--init) MODE=init ;;
	-*) usage ;;
	*)
		[ -f "$1" ] || fail check "reason=no_jar path=$1"
		SRC="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
		;;
esac
cd "$(dirname "$0")"

# 배포 워크플로와 손으로 부른 것이 겹치지 않게 한다.
exec 9> .swap.lock
flock -n 9 || fail lock "reason=another_swap_running"

# 받는 쪽 파일을 그 색으로 쓴다. 제자리에 고쳐 쓴다(compose.yaml 의 마운트 설명).
write_upstream() {
	printf '%s\n' \
		"# swap.sh 가 쓴다(결정 046). 손으로 고치지 않는다 — 받는 쪽을 바꾸려면 ./swap.sh" \
		"reverse_proxy api-$1:8080 {" \
		"	health_uri /health" \
		"	health_interval 2s" \
		"	lb_try_duration 15s" \
		"}" > upstream.caddy
}

if [ "$MODE" = init ]; then
	[ ! -e upstream.caddy ] || fail init "reason=exists (받는 쪽은 ./swap.sh 로만 바꾼다)"
	write_upstream blue
	log "swap_init upstream=api-blue"
	exit 0
fi

[ -f .env ] || fail check "reason=no_env"
[ -f upstream.caddy ] || fail check "reason=no_upstream_caddy (docs/deploy.md 블루 · 그린으로 처음 옮겨 가기)"
# 옛 방식의 서버 컨테이너(api)가 남아 있으면 아직 옮겨 가지 않은 VM 이다.
if [ -n "$(docker ps -aq --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-byeolmyeong}" --filter label=com.docker.compose.service=api)" ]; then
	fail check "reason=legacy_api_container (docs/deploy.md 블루 · 그린으로 처음 옮겨 가기)"
fi

in_caddy() { docker compose exec -T caddy "$@"; }

# Caddy 가 지금 실제로 보내는 색(blue · green · none). 관리 API 의 살아 있는 설정을 읽는다. 읽지 못하면 실패.
# 관리 API 는 127.0.0.1 에만 듣는다(컨테이너의 localhost 는 ::1 로 풀려 닿지 않는다).
caddy_color() {
	local config blue=0 green=0
	config="$(in_caddy wget -qO- http://127.0.0.1:2019/config/apps/http/servers)" || return 1
	grep -Eq '"dial": ?"api-blue:8080"' <<< "$config" && blue=1
	grep -Eq '"dial": ?"api-green:8080"' <<< "$config" && green=1
	case "$blue$green" in 10) echo blue ;; 01) echo green ;; 00) echo none ;; *) echo both ;; esac
}

# ① 지금 색과 쉬는 색
ACTIVE="$(caddy_color)" || fail detect "reason=caddy_unreadable (Caddy 가 떠 있는지: docker compose ps caddy)"
case "$ACTIVE" in
	blue) IDLE=green ;;
	green | none) IDLE=blue ;;
	*) fail detect "reason=caddy_uses_$ACTIVE" ;;
esac
if [ -f active_color ] && [ "$(cat active_color)" != "$ACTIVE" ]; then
	log "swap_active_color_mismatch file=$(cat active_color) caddy=$ACTIVE (Caddy 를 따른다)"
fi

# ② jar
if [ "$MODE" = restart ]; then
	[ "$ACTIVE" != none ] || fail check "reason=nothing_to_restart"
	SRC="$PWD/app-$ACTIVE.jar"
fi
[ -s "$SRC" ] || fail check "reason=empty_jar path=$SRC"
log "swap_started mode=$MODE from=$ACTIVE to=$IDLE"
cp -f "$SRC" ".app-$IDLE.jar.part"
mv -f ".app-$IDLE.jar.part" "app-$IDLE.jar"

stop_idle() { docker compose stop "api-$IDLE" > /dev/null 2>&1 || true; }

# ③ 쉬는 색을 새로 만든다. --no-deps: Postgres 를 다시 만들거나 띄우지 않는다(설정이 바뀌었어도).
docker compose up -d --no-deps --force-recreate "api-$IDLE" || { stop_idle; fail start "color=$IDLE active=$ACTIVE"; }

# ④ 받는 쪽을 바꾸기 전에 그 색이 살아 있는지 Caddy 가 보는 길로 확인한다.
STARTED=$SECONDS
until in_caddy wget -q -O /dev/null -T 3 "http://api-$IDLE:8080/health" 2> /dev/null; do
	if [ $((SECONDS - STARTED)) -ge "$HEALTH_WAIT_SECONDS" ]; then
		stop_idle
		fail health "color=$IDLE waited=${HEALTH_WAIT_SECONDS}s active=$ACTIVE (docker compose logs --tail 100 api-$IDLE)"
	fi
	sleep 2
done
log "swap_healthy color=$IDLE seconds=$((SECONDS - STARTED))"

# ⑤ 받는 쪽을 바꾼다. 다시 읽기가 실패하면 Caddy 는 옛 설정을 그대로 쓴다.
# 다시 읽기의 결과와 상관없이 Caddy 가 실제로 쓰는 색을 다시 읽고 정한다(적용된 뒤 exec 만 실패할 수도 있다).
PREVIOUS="$(cat upstream.caddy)"
write_upstream "$IDLE"
RELOAD_OK=1
RELOAD_ERROR="$(in_caddy caddy reload --config /etc/caddy/Caddyfile 2>&1)" || RELOAD_OK=0
NOW="$(caddy_color)" || fail verify "reason=caddy_unreadable reload_ok=$RELOAD_OK (아무 색도 내리지 않았다 — docker compose ps 로 두 색을 본다)"
if [ "$NOW" != "$IDLE" ]; then
	if [ "$NOW" = "$ACTIVE" ]; then
		# 받는 쪽은 옛 색 그대로다(설정 오류, 또는 upstream.caddy 를 바꿔쳐 컨테이너가 옛 파일을 보는 경우 등). 파일을 되돌리고 새 색을 내린다.
		printf '%s\n' "$PREVIOUS" > upstream.caddy
		stop_idle
	fi
	# 그 밖(none · both)이면 무엇을 쓰는지 몰라 아무 색도 내리지 않는다.
	[ "$RELOAD_OK" = 1 ] || fail reload "caddy=$NOW active=$ACTIVE error=$(tr '\n' ' ' <<< "$RELOAD_ERROR" | cut -c1-300)"
	fail verify "caddy=$NOW expected=$IDLE"
fi
[ "$RELOAD_OK" = 1 ] || log "swap_reload_error_but_switched caddy=$NOW"
echo "$IDLE" > active_color
log "swap_switched from=$ACTIVE to=$IDLE"

# ⑥ 받던 요청이 Caddy 에서 빠질 틈을 두고, 옛 색을 차분하게 내린다(stop_grace_period 까지 기다린다).
if [ "$ACTIVE" != none ]; then
	sleep "$DRAIN_DELAY_SECONDS"
	STOPPING=$SECONDS
	if docker compose stop "api-$ACTIVE" > /dev/null; then
		log "swap_old_stopped color=$ACTIVE seconds=$((SECONDS - STOPPING))"
	else
		# 받는 쪽은 이미 새 색이라 끊기지 않는다. 옛 색이 남아 메모리만 쓴다.
		log "swap_old_stop_failed color=$ACTIVE (docker compose stop api-$ACTIVE)" >&2
	fi
fi

# ⑦ 마지막 정상 jar(지금 받는 jar)와 그 앞 버전(되돌리기용). 같은 jar 로 다시 띄웠으면(--restart) 앞 버전은 그대로 둔다.
if [ -f app.jar.good ] && ! cmp -s "app-$IDLE.jar" app.jar.good; then
	mv -f app.jar.good app.jar.prev
fi
cp -f "app-$IDLE.jar" .app.jar.good.part
mv -f .app.jar.good.part app.jar.good
log "swap_done active=$IDLE"
