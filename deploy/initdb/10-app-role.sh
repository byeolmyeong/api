#!/usr/bin/env bash
# Postgres 컨테이너가 데이터 디렉터리를 처음 만들 때 한 번만 돈다(이미지의 /docker-entrypoint-initdb.d, 결정 020).
# 서버가 쓸 일반 계정을 만들고 DB byeolmyeong 의 소유자로 둔다. 소유자라 Flyway 마이그레이션은 되고, 최고 관리자 권한은 없다.
# 이름·비밀번호는 compose 가 APP_DB_USER·APP_DB_PASSWORD 로 넘긴다(.env 의 DATABASE_USERNAME·DATABASE_PASSWORD).
# 여기서 실패하면 계정·DB 없이 데이터 디렉터리만 남고 다음 시작부터는 이 스크립트가 돌지 않는다. 그래서 값을 먼저 검사한다.
# 복구: 데이터가 없으니 볼륨을 지우고 다시 띄운다(docs/deploy.md "처음 초기화가 실패하면").
set -euo pipefail

if [ -z "${APP_DB_USER:-}" ] || [ -z "${APP_DB_PASSWORD:-}" ]; then
	echo "initdb: DATABASE_USERNAME·DATABASE_PASSWORD 가 비어 있다" >&2
	exit 1
fi
if [ "$APP_DB_USER" = postgres ]; then
	echo "initdb: DATABASE_USERNAME 은 postgres 가 아닌 이름이어야 한다(최고 관리자와 따로 둔다)" >&2
	exit 1
fi

# 실패한 문장을 서버 로그에 남기지 않는다. 문장에 비밀번호가 들어 있다.
PGOPTIONS='-c log_min_error_statement=panic' psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres \
	-v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" <<'SQL'
create role :"app_user" login password :'app_password';
create database byeolmyeong owner :"app_user";
SQL
