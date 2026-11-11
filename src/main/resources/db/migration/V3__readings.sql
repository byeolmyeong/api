-- 요약 해석. 검증(구조·근거 대조)을 통과한 글만 넣는다. 계정을 지우면 함께 지워진다.
create table readings (
	id uuid primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	-- 글의 종류. 지금은 SUMMARY(요약 해석)
	kind text not null,
	-- 차트를 정하는 입력(UTC 시각·시각을 아는지·위경도)의 SHA-256(16진수). 출생 정보 원문은 profiles 에만 둔다.
	chart_hash text not null,
	-- 검증을 통과한 글: { headline, sections[{ title, body, basis[] }], strengths[], cautions[] }
	body jsonb not null,
	-- 공유 주소에 쓰는 무작위 값
	share_id text not null unique,
	created_at timestamptz not null default now(),
	-- 같은 소유자·종류·차트의 글은 하나다. 동시에 만들어도 한 행만 남는다.
	unique (owner_id, kind, chart_hash)
);
