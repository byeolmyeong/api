-- 이용 정지 · 해제 내역(결정 044 의 1 — 해제 사유도 남긴다). 감사 기록은 코드 값만 담으므로 관리자가 쓴 사유는 여기에 둔다.
-- 더하기만 한다. 계정을 지우면 함께 지워진다.
create table account_suspensions (
	id uuid primary key,
	account_id uuid not null references accounts (id) on delete cascade,
	action text not null check (action in ('SUSPEND', 'UNSUSPEND')),
	-- 관리자가 쓴 사유(1~200자). 앱에는 보이지 않는다
	reason text not null,
	admin_id uuid not null references admin_accounts (id),
	created_at timestamptz not null default now()
);

create index account_suspensions_account_id on account_suspensions (account_id, created_at);
