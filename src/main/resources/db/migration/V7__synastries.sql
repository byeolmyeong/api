-- 궁합(직접 입력). 상대의 출생 정보·행성 위치·두 차트 사이의 각도는 저장하지 않는다(결정 008).
-- 남기는 것은 부를 이름·관계 유형·서버가 계산한 점수·검증을 통과한 글뿐이다. 계정을 지우면 함께 지워진다.
create table synastries (
	id uuid primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	-- 상대를 부를 이름(소유자가 적은 것)
	name text not null,
	relation text not null check (relation in ('LOVE', 'FRIEND', 'WORK', 'FAMILY')),
	-- 나 기준 점수
	score integer not null check (score between 40 and 100),
	-- 한 줄 요약
	headline text not null,
	-- 관계 키워드 3개: ["…", "…", "…"]
	keywords jsonb not null,
	-- 계산할 때의 내 차트 해시(readings.chart_hash 와 같은 값). 내 출생 정보가 바뀌었는지 가린다.
	owner_chart_hash text not null,
	created_at timestamptz not null default now()
);

-- 내 목록과 관계 유형별 랭킹
create index synastries_owner_relation_score on synastries (owner_id, relation, score desc);

-- 궁합 리포트(끌림·대화·갈등·오래가기). 궁합마다 한 행이고, 뒤에서 글을 쓰는 동안의 상태를 함께 둔다(reports 와 같은 방식).
create table synastry_reports (
	synastry_id uuid primary key references synastries (id) on delete cascade,
	-- WRITING(쓰는 중) · DONE(완성) · FAILED(만들지 못함, 다시 요청하면 새로 시작)
	status text not null check (status in ('WRITING', 'DONE', 'FAILED')),
	-- 검증을 통과한 글: { sections[{ area, title, paragraphs[{ title, body, basis[] }] }] }. DONE 일 때만 있다. 근거는 내 쪽 요소뿐이다.
	body jsonb,
	-- 지금의(마지막) 생성을 시작한 때. WRITING 인 채 10분이 지나면 멈춘 것으로 본다.
	started_at timestamptz not null default now(),
	updated_at timestamptz not null default now(),
	check ((status = 'DONE') = (body is not null))
);
