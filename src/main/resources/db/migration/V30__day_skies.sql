-- 그날의 하늘(결정 034): 소유자 · 차트 · 날짜마다 한 번 만들어 저장하고 다시 열면 그대로 준다. 오늘의 하늘은 오늘 날짜의 행이다.
-- 검증을 통과한 글만 넣는다(실패는 저장하지 않는다). 계정을 지우면 함께 지워진다. 더하기만 한다.
create table day_skies (
	id uuid primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	-- 차트를 정하는 입력의 SHA-256(16진수). readings · detail_reports 의 chart_hash 와 같은 값이다. 출생 정보를 고치면 값이 달라 새로 만든다(옛 행은 남는다).
	chart_hash text not null,
	-- 그날(한국 날짜)
	date date not null,
	-- 검증을 통과한 글: { title, category, flow{ people, work, mind }, reason, practices[{ title }] } (docs/architecture.md "API 계약" 의 DaySky)
	body jsonb not null,
	-- 만든 때(응답의 createdAt)
	created_at timestamptz not null default now(),
	-- 같은 소유자 · 차트 · 날짜의 글은 하나다. 동시에 만들어도 한 행만 남는다.
	unique (owner_id, chart_hash, date)
);
