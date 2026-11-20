-- 앱 설정에 플랫폼별 최신 버전(결정 040). 이보다 낮은 앱에 업데이트를 권한다(앱이 판정한다 — 서버는 막지 않는다).
-- 더하기만 한다: 없으면 null(권하지 않는다). 막기는 그대로 min_version · block_now(결정 016).
alter table app_configs add column latest_version text;
