-- 점검(결정 017). 행은 하나뿐이고 여기서 꺼진 채로 넣는다. 켜면 앱의 모든 요청이 503 MAINTENANCE 가 된다.
-- 관리자가 PUT /admin/app-config/maintenance 로 켜고 끈다.

create table app_maintenance (
	-- 행을 하나로 묶는 기본 키(언제나 true)
	id boolean primary key default true check (id),
	active boolean not null default false,
	-- 앱이 점검 안내에 보여 줄 글. 없으면 null
	message text,
	updated_at timestamptz not null default now(),
	-- 마지막으로 고친 관리자. 아무도 고치지 않았으면 null
	updated_by uuid references admin_accounts (id)
);

insert into app_maintenance (id) values (true);
