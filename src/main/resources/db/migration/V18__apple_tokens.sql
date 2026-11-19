-- 애플 계정의 refresh token(결정 021). 탈퇴할 때 애플에 철회를 부르려고 둔다(App Store 심사 지침 5.1.1(v)).
-- client_id 는 그 토큰을 받은 클라이언트(iOS 번들 ID 또는 웹 Services ID)이고 철회할 때 같은 값을 써야 한다.
create table apple_tokens (
	account_id uuid primary key references accounts (id) on delete cascade,
	client_id text not null,
	refresh_token text not null,
	updated_at timestamptz not null default now()
);

-- 처음 온 애플 사용자는 가입을 마칠 때까지 가입권에 토큰을 둔다(authorization code 는 5분만 유효해 로그인 때 바로 바꾼다).
alter table signup_tickets add column apple_client_id text;
alter table signup_tickets add column apple_refresh_token text;
