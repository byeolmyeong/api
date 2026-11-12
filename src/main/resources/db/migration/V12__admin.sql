-- 관리자 API(결정 014). 관리자 계정·세션은 앱 사용자(accounts·sessions)와 아무 관계가 없다.

create table admin_accounts (
	id uuid primary key,
	-- 로그인 아이디. 소문자로 저장한다.
	login_id text not null unique,
	-- bcrypt 해시. 비밀번호 원문은 저장하지 않는다.
	password_hash text not null,
	-- 화면에 보이는 이름
	name text not null,
	-- 꺼진 관리자는 로그인할 수 없다. 관리자는 지우지 않는다(처리 기록이 가리킨다).
	active boolean not null default true,
	created_at timestamptz not null default now()
);

create table admin_sessions (
	-- 세션 토큰의 SHA-256(16진수). 토큰 원문은 저장하지 않는다.
	token_hash text primary key,
	admin_id uuid not null references admin_accounts (id) on delete cascade,
	created_at timestamptz not null default now(),
	-- 이때가 지나면 쓸 수 없다(로그인한 때 + 12시간)
	expires_at timestamptz not null
);

create index admin_sessions_admin_id on admin_sessions (admin_id);

-- 문의 처리. 이미 있는 문의는 모두 NEW 가 된다.
alter table inquiries
	add column status text not null default 'NEW' check (status in ('NEW', 'IN_PROGRESS', 'DONE')),
	-- 관리자끼리 보는 메모. 없으면 null
	add column admin_memo text,
	-- 상태나 메모를 마지막으로 바꾼 관리자와 그때. 아무도 손대지 않았으면 null
	add column handled_by uuid references admin_accounts (id),
	add column handled_at timestamptz;

-- 관리자 목록은 새것부터 본다
create index inquiries_created_at on inquiries (created_at desc);

-- 자주 묻는 질문. 처음 글은 넣지 않는다(관리 화면으로 넣는다).
create table faqs (
	id uuid primary key,
	question text not null,
	answer text not null,
	-- 작은 것부터 보인다
	sort_order integer not null,
	-- 공개한 것만 GET /faqs 에 나온다
	published boolean not null default false,
	updated_at timestamptz not null default now(),
	updated_by uuid references admin_accounts (id)
);
