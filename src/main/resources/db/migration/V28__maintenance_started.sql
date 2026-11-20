-- 점검을 켠 때와 켠 관리자(결정 043 의 7 — 관리 화면 모든 화면 위의 띠). 더하기만 한다(앞으로만 호환 — 옛 서버는 이 칸을 모른 채 돈다).
-- 꺼진 상태에서 켤 때만 적고, 켜진 채로 글만 바꾸면 그대로, 끄면 둘 다 null. 공개 GET /app-config 는 이 칸을 읽지 않는다.
alter table app_maintenance
	add column started_at timestamptz,
	-- 관리자는 지우지 않는다(결정 014)
	add column started_by uuid references admin_accounts (id);

-- 켜진 채로 올라가면 언제 켰는지 모르므로 마지막으로 고친 때 · 관리자로 채운다(꺼져 있으면 null 그대로).
update app_maintenance set started_at = updated_at, started_by = updated_by where active;
