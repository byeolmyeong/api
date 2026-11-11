-- 계정·세션·출생 정보. 계정을 지우면 그 사람의 행이 함께 지워지도록 모두 계정을 가리킨다(on delete cascade).
-- 뒤에 생기는 소유자 테이블(글·궁합 등)도 같은 방식으로 계정을 가리킨다.

create table accounts (
	id uuid primary key,
	-- 로그인 공급자: DEV(개발용), 뒤에 GOOGLE · APPLE · KAKAO
	provider text not null,
	-- 공급자가 준 사용자 식별자. 이메일 같은 다른 정보는 받지 않는다.
	provider_user_id text not null,
	created_at timestamptz not null default now(),
	unique (provider, provider_user_id)
);

create table sessions (
	-- 세션 토큰의 SHA-256(16진수). 토큰 원문은 저장하지 않는다.
	token_hash text primary key,
	account_id uuid not null references accounts (id) on delete cascade,
	created_at timestamptz not null default now()
);

create index sessions_account_id on sessions (account_id);

create table profiles (
	account_id uuid primary key references accounts (id) on delete cascade,
	birth_date date not null,
	-- 태어난 시각. 모르면 null
	birth_time time,
	-- 출생지 목록(GET /places)의 id
	place_id integer not null,
	updated_at timestamptz not null default now()
);
