-- 알림함(결정 035 · 앱 계획의 INQUIRY_ANSWERED 더하기). 더하기만 한다(앞으로만 호환).
-- 계정을 지우면 함께 지워진다. 궁합 · 문의가 지워지면 그 알림도 지워진다(누르면 404 가 나는 줄을 남기지 않는다 — 결정 035 의 6).
create table notifications (
	id uuid primary key,
	owner_id uuid not null references accounts (id) on delete cascade,
	kind text not null check (kind in ('READING_DONE', 'ME_REPORT_DONE', 'TIMING_REPORT_DONE', 'SYNASTRY_REPORT_DONE', 'INQUIRY_ANSWERED')),
	-- 궁합 리포트 완성 알림만
	synastry_id uuid references synastries (id) on delete cascade,
	-- 문의 답변 알림만
	inquiry_id uuid references inquiries (id) on delete cascade,
	created_at timestamptz not null default now(),
	-- 누른 때. null 이면 안 읽음
	read_at timestamptz
);

create index notifications_owner_created on notifications (owner_id, created_at desc);
