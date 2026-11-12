-- 앱 설정(결정 016): 플랫폼마다 한 행. 최소 버전과, 급할 때 그보다 낮은 버전의 요청을 막을지(block_now).
-- 두 행은 여기서 넣고 더하거나 지우지 않는다. 값은 관리자가 PUT /admin/app-config/{platform} 으로 고친다.

create table app_configs (
	platform text primary key check (platform in ('ios', 'android')),
	-- 점으로 나눈 1~3개의 정수("1.2.0"). 없으면 null(정한 최소 버전이 없다)
	min_version text,
	-- 업데이트하러 갈 스토어 주소(https). 없으면 null
	store_url text,
	-- 앱이 업데이트 안내에 보여 줄 글. 없으면 null
	message text,
	-- 켜면 min_version 보다 낮은 버전의 요청을 426 으로 막는다. 최소 버전이 있어야 켤 수 있다.
	block_now boolean not null default false check (not block_now or min_version is not null),
	updated_at timestamptz not null default now(),
	-- 마지막으로 고친 관리자. 아무도 고치지 않았으면 null
	updated_by uuid references admin_accounts (id)
);

insert into app_configs (platform) values ('ios'), ('android');
