-- 공지사항(결정 055 · 앱 결정 107). 더하기만 한다(앞으로만 호환 — 옛 서버는 이 표 · 칸을 모른 채 돈다).
-- 관리자가 쓰고(admin), 앱은 공개한 것만 읽는다(notice). 지우면 그 공지의 알림함 줄도 지워진다.
create table notices (
	id uuid primary key,
	-- 공지(점검 · 약관 · 운영 안내) · 업데이트(새 기능 · 판 소식)
	category text not null check (category in ('NOTICE', 'UPDATE')),
	-- 1 ~ 60자 · 본문 1 ~ 3000자(서버가 본다). 본문은 꾸밈 없는 글자
	title text not null,
	body text not null,
	status text not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED')),
	-- 공개 중일 때만 있다(내리면 비운다)
	published_at timestamptz,
	-- 알림 보내기 스위치. 업데이트 갈래는 켤 수 없다
	notify boolean not null default false,
	-- 알림 보내기(알림함 · 푸시)를 시작한 때. 한 글에 한 번 — 이 칸을 먼저 적은 요청만 보낸다
	notify_started_at timestamptz,
	-- 푸시 결과: 대상 기기 · Expo 가 받은 수 · 실패 · 보내지 않은 수. push 가 다 보낸 뒤 채운다(그 전에는 null)
	push_devices integer,
	push_sent integer,
	push_failed integer,
	push_skipped integer,
	-- 알림함 결과: 넣은 줄 수 · 넣기가 실패했는지. notification 이 마친 뒤 채운다(그 전에는 null)
	inbox_added integer,
	inbox_failed boolean,
	created_at timestamptz not null default now(),
	created_by uuid references admin_accounts (id),
	updated_at timestamptz not null default now(),
	updated_by uuid references admin_accounts (id),
	check ((status = 'PUBLISHED') = (published_at is not null)),
	check (not (category = 'UPDATE' and notify))
);

-- 앱의 목록은 공개한 것만 새것부터 본다
create index notices_published_at on notices (published_at desc) where status = 'PUBLISHED';

-- 알림함에 공지 종류를 더한다(결정 055 의 5). 계정마다 한 줄, 공지를 지우면 함께 지워진다(누르면 없는 글로 가는 줄을 남기지 않는다).
alter table notifications drop constraint notifications_kind_check;
alter table notifications add constraint notifications_kind_check
	check (kind in ('READING_DONE', 'ME_REPORT_DONE', 'TIMING_REPORT_DONE', 'SYNASTRY_REPORT_DONE', 'INQUIRY_ANSWERED', 'NOTICE'));
-- 공지 알림만
alter table notifications add column notice_id uuid references notices (id) on delete cascade;
