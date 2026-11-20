-- 자세한 풀이(결정 029): 나 탭 풀이(ME)와 시기 풀이(TIMING)를 한 테이블에 kind 로 나눈다. 꼴은 reports(V4 · V9)와 같다.
-- 소유자 · 종류 · 차트마다 한 행이고, 뒤에서 글을 쓰는 동안의 상태를 함께 둔다. 계정을 지우면 함께 지워진다.
-- 더하기만 한다: 옛 reports 테이블과 그 행은 그대로 둔다(옛 POST /reports 가 계획 S⑦ 까지 쓴다).
create table detail_reports (
	id uuid primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	-- ME(나 탭 풀이) · TIMING(시기 풀이)
	kind text not null check (kind in ('ME', 'TIMING')),
	-- 차트를 정하는 입력의 SHA-256(16진수). readings · reports 의 chart_hash 와 같은 값이다. 출생 정보 원문은 profiles 에만 둔다.
	chart_hash text not null,
	-- WRITING(쓰는 중) · DONE(완성) · FAILED(만들지 못함, 다시 요청하면 새로 시작)
	status text not null check (status in ('WRITING', 'DONE', 'FAILED')),
	-- 검증을 통과한 글. DONE 일 때만 있다. 모양은 종류마다 다르다(docs/architecture.md "API 계약" 의 MeReport · TimingReport).
	body jsonb,
	-- 지금의(마지막) 생성을 시작한 때. 그 생성의 표식으로만 쓴다(끝낼 때 아직 내 생성인지 가린다).
	started_at timestamptz not null default now(),
	-- 상태가 마지막으로 바뀐 때
	updated_at timestamptz not null default now(),
	-- 생성을 맡은 서버가 살아 있다는 표시. WRITING 인 채 2분 넘게 끊기면 멈춘 것으로 본다(V9 와 같다).
	active_at timestamptz not null default now(),
	-- 같은 소유자 · 종류 · 차트의 풀이는 하나다. 동시에 요청해도 한 행만 생긴다.
	unique (owner_id, kind, chart_hash),
	check ((status = 'DONE') = (body is not null))
);
