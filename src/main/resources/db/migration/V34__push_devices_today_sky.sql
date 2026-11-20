-- 푸시(결정 042). 더하기만 한다(앞으로만 호환 — 옛 서버는 이 표 · 칸을 모른 채 돈다).

-- 기기 토큰(Expo 푸시 토큰). 한 기기는 한 계정에만 — 다른 계정이 같은 기기에서 등록하면 그 계정으로 옮긴다.
-- 계정을 지우면 함께 지워져 그 기기로 푸시가 가지 않는다.
create table push_devices (
	expo_token text primary key,
	account_id uuid not null references accounts (id) on delete cascade,
	platform text not null check (platform in ('IOS', 'ANDROID')),
	-- 등록할 때의 X-App-Version. 없거나 읽을 수 없으면 null
	app_version text,
	created_at timestamptz not null default now(),
	-- 마지막으로 등록한(앱이 다시 보낸) 때
	last_seen_at timestamptz not null default now()
);

create index push_devices_account_id on push_devices (account_id);

alter table accounts
	-- 오늘의 하늘 알림 켜기. null 이면 푸시 수신 동의(push_consented_at)를 따른다(앱 결정 057 의 4)
	add column today_sky_push boolean,
	-- 오늘의 하늘 알림을 받는 한국 시각(5분 단위)
	add column today_sky_time time not null default '08:00';

-- 오늘의 하늘 아침 알림의 하루 한 번(결정 042 의 6). (계정, 한국 날짜)마다 한 줄 — 먼저 넣은 서버만 미리 만들고, 보낸 때를 먼저 적은 서버만 보낸다.
-- 서버 여러 대 · 재시작에도 같은 날 두 번 가지 않는다. 계정을 지우면 함께 지워진다.
create table today_sky_pushes (
	account_id uuid not null references accounts (id) on delete cascade,
	kst_date date not null,
	-- 미리 만들기를 맡은 때(그 줄을 넣은 때)
	created_at timestamptz not null default now(),
	-- 보낸 때. null 이면 아직
	sent_at timestamptz,
	primary key (account_id, kst_date)
);
