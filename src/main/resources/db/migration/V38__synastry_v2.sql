-- 궁합 v2(결정 053): 직접 넣은 상대의 출생 정보를 저장하고, 한 번에 만들기의 상태를 궁합 행에 둔다. V7 의 "상대 출생 정보를 저장하지 않는다(결정 010)" 를 대체한다.
-- 더하기와 NOT NULL 풀기만 한다(결정 046 — 배포 중 옛 jar 가 같은 DB 를 쓴다). 옛 jar 가 넣는 행은 기본값(DONE · DIRECT)으로 맞다.
-- 상대 출생 정보는 그 궁합에만 쓴다(점수 · 이유 · 글 다시 쓰기). 같은 행이라 궁합을 지우거나 계정을 지우면(on delete cascade) 함께 지워진다.
alter table synastries
	-- DIRECT(직접 입력) · LINK(내 궁합 링크 — 계획 S③)
	add column source text not null default 'DIRECT' check (source in ('DIRECT', 'LINK')),
	-- WRITING(쓰는 중) · DONE(완성) · FAILED(만들지 못함 — 다시 하기로 새로 시작) · NEW_STAR(링크로 온 사람, 사이를 고르기 전 — 계획 S③)
	add column status text not null default 'DONE' check (status in ('WRITING', 'DONE', 'FAILED', 'NEW_STAR')),
	-- 직접 넣은 상대의 출생 정보. 시각을 모르면 시각 · 곳이 없다. 옛 행(결정 010)과 링크 행에는 없다.
	add column partner_birth_date date,
	add column partner_birth_time time,
	add column partner_place_id integer,
	-- 지금의(마지막) 생성을 시작한 때. 그 생성의 표식으로만 쓴다(끝낼 때 아직 내 생성인지 가린다 — detail_reports 와 같은 꼴).
	add column started_at timestamptz,
	-- 생성을 맡은 서버가 살아 있다는 표시. WRITING 인 채 2분 넘게 끊기면 멈춘 것으로 본다.
	add column active_at timestamptz,
	-- 완성된 때 · 앱이 완성된 결과를 받아 간 때 · "나왔어요" 알림을 보낸 때(결정 053 의 8)
	add column done_at timestamptz,
	add column seen_at timestamptz,
	add column notified_at timestamptz,
	add constraint synastries_partner_time_needs_date check (partner_birth_time is null or partner_birth_date is not null);

-- 쓰는 중 · 새로 온 별에는 한 줄 요약 · 키워드가 아직 없고, 새로 온 별에는 사이 · 점수도 없다.
alter table synastries
	alter column relation drop not null,
	alter column score drop not null,
	alter column headline drop not null,
	alter column keywords drop not null;

alter table synastries add constraint synastries_status_fields check (
	(status = 'NEW_STAR' or (relation is not null and score is not null))
	and (status <> 'DONE' or (headline is not null and keywords is not null))
);

-- 하루 안전 한도: 그날(한국 날짜) 만든 궁합 수를 센다(결정 053 의 6).
create index synastries_owner_created on synastries (owner_id, created_at);

-- "나왔어요" 알림을 아직 보내지 않은 완성(결정 053 의 8). 받아 가거나 보내면 빠져서 작게 남는다.
create index synastries_ready_unnoticed on synastries (done_at) where done_at is not null and seen_at is null and notified_at is null;
