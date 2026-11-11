-- 심층 리포트. 소유자·차트마다 한 행이고, 뒤에서 글을 쓰는 동안의 상태를 함께 둔다. 계정을 지우면 함께 지워진다.
create table reports (
	id uuid primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	-- 차트를 정하는 입력의 SHA-256(16진수). readings.chart_hash 와 같은 값이다. 출생 정보 원문은 profiles 에만 둔다.
	chart_hash text not null,
	-- WRITING(쓰는 중) · DONE(완성) · FAILED(만들지 못함, 다시 요청하면 새로 시작)
	status text not null check (status in ('WRITING', 'DONE', 'FAILED')),
	-- 검증을 통과한 글: { sections[{ area, title, paragraphs[{ title, body, basis[] }] }] }. DONE 일 때만 있다.
	body jsonb,
	-- 지금의(마지막) 생성을 시작한 때. WRITING 인 채 10분이 지나면 멈춘 것으로 본다.
	started_at timestamptz not null default now(),
	-- 상태가 마지막으로 바뀐 때
	updated_at timestamptz not null default now(),
	-- 같은 소유자·차트의 리포트는 하나다. 동시에 요청해도 한 행만 생긴다.
	unique (owner_id, chart_hash),
	check ((status = 'DONE') = (body is not null))
);
