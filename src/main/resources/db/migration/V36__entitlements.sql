-- 자세한 풀이 상품(결정 056, 앱 결정 101). 더하기만 한다.

-- 상품을 가진 계정. 출처마다 한 줄: LEGACY(1.0.0 에서 받은 계정 — 결제 시작 전까지 서버가 채운다) · STORE(스토어에서 산 계정 — RevenueCat 으로 확인).
-- 출생 정보와 상관없는 계정의 것이라 출생 정보를 바꿔도 남는다(다시 받기는 무료). 계정을 지우면 함께 지워진다.
create table entitlements (
	account_id uuid not null references accounts (id) on delete cascade,
	source text not null check (source in ('LEGACY', 'STORE')),
	created_at timestamptz not null default now(),
	-- STORE 일 때 결제 시트에서 보여 준 고지(청약철회 안내)의 판 — 앱이 확인 요청에 실어 보낸 짧은 글자(앱 결정 101 의 5-1). 없으면 null.
	notice_version text,
	primary key (account_id, source)
);

-- 결제 없이 여는 계정(앱 결정 101 의 5-2). 계정 이메일(accounts.email)이 여기 있으면 상품을 가진 것으로 본다.
-- 이메일은 소문자로 둔다(대소문자를 가리지 않고 맞춘다). 넣고 빼기는 관리자가 하고 감사 기록에 남는다(이메일은 감사 기록에 넣지 않는다).
create table entitlement_allowlist (
	id uuid primary key,
	email text not null unique,
	created_at timestamptz not null default now(),
	created_by uuid not null references admin_accounts (id)
);
