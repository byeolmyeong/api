-- 이용 정지(결정 044 의 1). 더하기만 한다(앞으로만 호환 — 옛 서버는 정지를 모른 채 돈다).
alter table accounts
	-- 정지한 때. null 이면 정지 아님
	add column suspended_at timestamptz,
	-- 정지한 관리자. 관리자는 지우지 않는다(결정 014)
	add column suspended_by uuid references admin_accounts (id),
	-- 정지 사유(관리자끼리 보는 기록 — 앱에는 보이지 않는다)
	add column suspension_reason text;
