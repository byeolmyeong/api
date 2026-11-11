-- 방향성 제시(해 볼 만한 것 3개). 검증(구조·근거 대조·조언형)을 통과한 글만 넣는다. 계정을 지우면 함께 지워진다. 결정 009.
create table directions (
	id uuid primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	-- 주제. 지금은 LOVE(이성). 나중에 FUTURE(미래)·SYNASTRY(궁합)
	topic text not null,
	-- 무엇에 대한 글인지. 뜻은 주제가 정한다: LOVE 는 차트를 정하는 입력의 SHA-256(readings.chart_hash 와 같은 값).
	-- 출생 정보 원문은 profiles 에만 둔다.
	target text not null,
	-- 검증을 통과한 글: { title, items[3 × { title, body, basis[] }] }
	body jsonb not null,
	created_at timestamptz not null default now(),
	-- 같은 소유자·주제·대상의 글은 하나다. 동시에 만들어도 한 행만 남는다.
	unique (owner_id, topic, target)
);
