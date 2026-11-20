-- 마지막 접속 · 날마다 접속(결정 043 의 2). 더하기만 한다(앞으로만 호환 — 옛 서버는 이 칸 · 표를 모른 채 돈다).
-- 사용자 세션으로 온 요청이 10분에 한 번만 쓴다. 감사 기록과 따로 둔다.
alter table accounts add column last_seen_at timestamptz;

-- 그날(한국 날짜) 처음 접속한 계정마다 한 줄. 접속 차트(결정 043 의 4)가 센다.
-- 계정을 지우면 함께 지워진다(그 사람의 모든 행을 지운다 — 결정 021).
create table daily_active (
	kst_date date not null,
	account_id uuid not null references accounts (id) on delete cascade,
	-- 그날 처음 접속한 한국 시각의 시(0~23). 오늘 시간대별 차트가 센다.
	first_hour_kst smallint not null check (first_hour_kst between 0 and 23),
	primary key (kst_date, account_id)
);

-- 계정을 지울 때 함께 지우는 줄을 찾는다.
create index daily_active_account_id on daily_active (account_id);
