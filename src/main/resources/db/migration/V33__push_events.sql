-- 광고 푸시 발송 내역(결정 043 의 6). admin 이 쓰고, 보낸 결과는 push 가 이벤트로 알려 admin 이 채운다.
-- 더하기만 한다(앞으로만 호환).
create table push_events (
	id uuid primary key,
	-- 보낸 한국 날짜. 하루 한 번(관리자 누구든)을 이 유일 제약이 지킨다 — 두 관리자가 동시에 보내도 한 줄만 들어간다
	kst_date date not null unique,
	sent_at timestamptz not null default now(),
	-- 관리자가 쓴 제목 · 본문(앞의 "(광고) " · 끝의 수신 거부 줄은 빼고) · 이동 화면 키
	title text not null,
	body text not null,
	target text not null,
	-- 보낼 때의 대상 수. push 가 다 보낸 뒤 실제 수로 고친다
	recipients integer not null,
	-- push 가 다 보낸 뒤 채운다. 그 전에는 null(보내는 중)
	succeeded integer,
	failed integer,
	sent_by uuid not null references admin_accounts (id)
);
