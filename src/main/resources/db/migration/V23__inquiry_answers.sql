-- 문의 v2(결정 039): 관리자의 답 · 임시 저장, 문의를 보낸 앱. 더하기만 한다 — 있던 열 · 행은 그대로다(앞으로만 호환).
alter table inquiries
	-- 앱 사용자에게 보이는 답. 답하지 않았으면 null. 고칠 수는 있어도 지울 수는 없다(서버 코드가 지킨다)
	add column answer_body text,
	-- 처음 답한 때와 그 관리자. 답을 고쳐도 바뀌지 않는다(이때 알림 이벤트를 한 번 낸다)
	add column answered_at timestamptz,
	add column answered_by uuid references admin_accounts (id),
	-- 관리자의 답 임시 저장. 앱에는 보이지 않는다. 답을 저장하면 비운다
	add column answer_draft text,
	-- 문의를 보낸 앱의 X-App-Platform(ios · android) · X-App-Version. 웹 · 헤더 없는 요청 · 이전 문의는 null
	add column app_platform text,
	add column app_version text;

-- 상태에 ANSWERED(관리자가 답을 저장함)를 더한다. 넓히기만 하므로 있던 행은 모두 맞는다.
alter table inquiries drop constraint inquiries_status_check;
alter table inquiries add constraint inquiries_status_check check (status in ('NEW', 'IN_PROGRESS', 'ANSWERED', 'DONE'));
