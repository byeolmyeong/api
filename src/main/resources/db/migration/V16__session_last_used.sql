-- 세션 만료(결정 020): 마지막 사용 후 byeolmyeong.auth.session-days 일이 지나면 끝난다. 기존 세션은 지금부터 센다.
alter table sessions add column last_used_at timestamptz not null default now();
