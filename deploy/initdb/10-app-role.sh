#!/usr/bin/env bash
# Postgres 컨테이너가 데이터 디렉터리를 처음 만들 때 한 번만 돈다(이미지의 /docker-entrypoint-initdb.d, 결정 020).
# 서버가 쓸 일반 계정을 만들고 DB byeolmyeong 의 소유자로 둔다. 소유자라 Flyway 마이그레이션은 되고, 최고 관리자 권한은 없다.
# 이름·비밀번호는 compose 가 APP_DB_USER·APP_DB_PASSWORD 로 넘긴다(.env 의 DATABASE_USERNAME·DATABASE_PASSWORD).
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres \
	-v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" <<'SQL'
create role :"app_user" login password :'app_password';
create database byeolmyeong owner :"app_user";
SQL
